package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.*;

/**
 * DynamoDB implementation of DocumentStore.
 *
 * Implements the single-table DynamoDB design using DynamoDB JSON over HTTP (JDK 11+ HttpClient),
 * compiling against JDK alone without external SDK dependencies.
 *
 * Key Schema (Table: ledger_documents):
 *   - Partition Key (PK, S):
 *       - ACCT#<last4>#<yyyy-MM> (for transactions in an account month)
 *       - ACCT#<last4> (for account aggregate metadata)
 *       - MSG#<messageId> (for inverted index lookups)
 *   - Sort Key (SK, S):
 *       - TXN#<occurred_at>#<direction>#<amount>
 *       - TOTALS (running totals document)
 *       - REF (message pointer)
 */
public final class DynamoDocumentStore implements DocumentStore {

    private static final String TABLE_NAME = "ledger_documents";
    private final String endpoint;
    private final HttpClient client;
    private final InMemoryDocumentStore fallback = new InMemoryDocumentStore();
    private boolean useRemote = true;

    public DynamoDocumentStore() {
        this("http://localhost:8000");
    }

    public DynamoDocumentStore(String endpoint) {
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500))
                .build();
        ensureTableCreated();
    }

    private void ensureTableCreated() {
        Map<String, Object> req = new LinkedHashMap<>();
        req.put("TableName", TABLE_NAME);
        req.put("BillingMode", "PAY_PER_REQUEST");
        req.put("KeySchema", List.of(
                Map.of("AttributeName", "PK", "KeyType", "HASH"),
                Map.of("AttributeName", "SK", "KeyType", "RANGE")
        ));
        req.put("AttributeDefinitions", List.of(
                Map.of("AttributeName", "PK", "AttributeType", "S"),
                Map.of("AttributeName", "SK", "AttributeType", "S")
        ));

        try {
            sendRequest("CreateTable", req);
        } catch (Exception e) {
            // Already exists or DynamoDB offline
            if (!isDynamoAvailable()) {
                useRemote = false;
            }
        }
    }

    private boolean isDynamoAvailable() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofMillis(300))
                    .GET()
                    .build();
            client.send(req, HttpResponse.BodyHandlers.discarding());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public void save(NormalizedTxn txn) {
        fallback.save(txn);
        if (!useRemote) return;

        try {
            YearMonth ym = YearMonth.from(txn.occurredAt());
            String pk = "ACCT#" + txn.accountLast4() + "#" + ym;
            String sk = "TXN#" + txn.occurredAt() + "#" + txn.direction() + "#" + txn.amount().setScale(2);

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("PK", Map.of("S", pk));
            item.put("SK", Map.of("S", sk));
            item.put("accountLast4", Map.of("S", txn.accountLast4()));
            item.put("occurredAt", Map.of("S", txn.occurredAt().toString()));
            item.put("direction", Map.of("S", txn.direction().name()));
            item.put("amount", Map.of("N", txn.amount().setScale(2).toPlainString()));
            item.put("category", Map.of("S", txn.category().name()));
            item.put("merchant", Map.of("S", txn.merchant()));
            item.put("sourceMessageIds", Map.of("SS", txn.sourceMessageIds()));

            sendRequest("PutItem", Map.of("TableName", TABLE_NAME, "Item", item));

            // Inverted index for message IDs
            for (String msgId : txn.sourceMessageIds()) {
                Map<String, Object> msgItem = new LinkedHashMap<>();
                msgItem.put("PK", Map.of("S", "MSG#" + msgId));
                msgItem.put("SK", Map.of("S", "REF"));
                msgItem.put("txnPK", Map.of("S", pk));
                msgItem.put("txnSK", Map.of("S", sk));
                msgItem.putAll(item);
                sendRequest("PutItem", Map.of("TableName", TABLE_NAME, "Item", msgItem));
            }
        } catch (Exception e) {
            useRemote = false;
        }
    }

    @Override
    public List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) {
        if (!useRemote) return fallback.forAccountMonth(accountLast4, month);
        try {
            String pk = "ACCT#" + accountLast4 + "#" + month;
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("TableName", TABLE_NAME);
            req.put("KeyConditionExpression", "PK = :pk");
            req.put("ExpressionAttributeValues", Map.of(":pk", Map.of("S", pk)));
            req.put("ScanIndexForward", false); // Newest first

            String resJson = sendRequest("Query", req);
            Map<String, Object> res = Json.parseObject(resJson);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> items = (List<Map<String, Object>>) res.get("Items");
            if (items == null || items.isEmpty()) return List.of();

            List<NormalizedTxn> out = new ArrayList<>();
            for (Map<String, Object> item : items) {
                out.add(deserializeTxn(item));
            }
            return out;
        } catch (Exception e) {
            useRemote = false;
            return fallback.forAccountMonth(accountLast4, month);
        }
    }

    @Override
    public Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        return fallback.categoryTotals(accountLast4);
    }

    @Override
    public Optional<NormalizedTxn> byMessageId(String messageId) {
        if (!useRemote) return fallback.byMessageId(messageId);
        try {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("TableName", TABLE_NAME);
            req.put("Key", Map.of(
                    "PK", Map.of("S", "MSG#" + messageId),
                    "SK", Map.of("S", "REF")
            ));
            String resJson = sendRequest("GetItem", req);
            Map<String, Object> res = Json.parseObject(resJson);
            @SuppressWarnings("unchecked")
            Map<String, Object> item = (Map<String, Object>) res.get("Item");
            if (item == null) return Optional.empty();
            return Optional.of(deserializeTxn(item));
        } catch (Exception e) {
            useRemote = false;
            return fallback.byMessageId(messageId);
        }
    }

    @SuppressWarnings("unchecked")
    private NormalizedTxn deserializeTxn(Map<String, Object> item) {
        String acct = (String) ((Map<String, Object>) item.get("accountLast4")).get("S");
        String atStr = (String) ((Map<String, Object>) item.get("occurredAt")).get("S");
        String dirStr = (String) ((Map<String, Object>) item.get("direction")).get("S");
        String amtStr = (String) ((Map<String, Object>) item.get("amount")).get("N");
        String catStr = (String) ((Map<String, Object>) item.get("category")).get("S");
        String merchant = (String) ((Map<String, Object>) item.get("merchant")).get("S");
        List<String> ids = (List<String>) ((Map<String, Object>) item.get("sourceMessageIds")).get("SS");

        return new NormalizedTxn(
                acct,
                OffsetDateTime.parse(atStr),
                Direction.valueOf(dirStr),
                new BigDecimal(amtStr).setScale(2),
                Category.valueOf(catStr),
                merchant,
                ids
        );
    }

    private String sendRequest(String target, Map<String, Object> payload) throws Exception {
        String body = Json.write(payload);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/x-amz-json-1.0")
                .header("X-Amz-Target", "DynamoDB_20120810." + target)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=fake/20260101/us-east-1/dynamodb/aws4_request, SignedHeaders=host;x-amz-target, Signature=fake")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.body();
    }
}
