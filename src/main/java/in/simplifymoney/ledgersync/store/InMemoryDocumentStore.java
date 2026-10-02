package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An in-memory implementation of DocumentStore.
 *
 * Implements the same key schema and direct access patterns as the DynamoDB model:
 * 1. Account-month partition (PK = ACCT#<last4>#<yyyy-MM>), sorted newest first
 * 2. Pre-aggregated running totals document (PK = ACCT#<last4>, SK = TOTALS)
 * 3. Message ID inverted index (PK = MSG#<messageId>)
 */
public final class InMemoryDocumentStore implements DocumentStore {

    private final Map<String, List<NormalizedTxn>> accountMonthTxns = new ConcurrentHashMap<>();
    private final Map<String, Map<Category, BigDecimal>> totals = new ConcurrentHashMap<>();
    private final Map<String, NormalizedTxn> messageIndex = new ConcurrentHashMap<>();
    private final Set<String> uniqueTxns = ConcurrentHashMap.newKeySet();

    private static String monthKey(String accountLast4, YearMonth month) {
        return accountLast4 + "#" + month;
    }

    private static String txnKey(NormalizedTxn t) {
        return t.accountLast4() + "#" + t.occurredAt() + "#" + t.direction() + "#" + t.amount().setScale(2);
    }

    @Override
    public synchronized void save(NormalizedTxn txn) {
        String key = txnKey(txn);
        if (!uniqueTxns.add(key)) {
            // Idempotent: already saved, merge sourceMessageIds into index
            for (String msgId : txn.sourceMessageIds()) {
                messageIndex.put(msgId, txn);
            }
            return;
        }

        // 1. Account month partition (newest first)
        YearMonth ym = YearMonth.from(txn.occurredAt());
        String mKey = monthKey(txn.accountLast4(), ym);
        List<NormalizedTxn> list = accountMonthTxns.computeIfAbsent(mKey, k -> new CopyOnWriteArrayList<>());
        list.add(txn);
        list.sort(Comparator.comparing(NormalizedTxn::occurredAt).reversed());

        // 2. Pre-aggregated running totals per category
        Map<Category, BigDecimal> acctTotals = totals.computeIfAbsent(txn.accountLast4(), k -> {
            Map<Category, BigDecimal> m = new EnumMap<>(Category.class);
            for (Category c : Category.values()) m.put(c, BigDecimal.ZERO.setScale(2));
            return m;
        });
        acctTotals.put(txn.category(), acctTotals.get(txn.category()).add(txn.amount()));

        // 3. Message index
        for (String msgId : txn.sourceMessageIds()) {
            messageIndex.put(msgId, txn);
        }
    }

    @Override
    public List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) {
        List<NormalizedTxn> list = accountMonthTxns.get(monthKey(accountLast4, month));
        return list == null ? List.of() : List.copyOf(list);
    }

    @Override
    public Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        Map<Category, BigDecimal> acctTotals = totals.get(accountLast4);
        if (acctTotals == null) {
            Map<Category, BigDecimal> empty = new EnumMap<>(Category.class);
            for (Category c : Category.values()) empty.put(c, BigDecimal.ZERO.setScale(2));
            return empty;
        }
        return Map.copyOf(acctTotals);
    }

    @Override
    public Optional<NormalizedTxn> byMessageId(String messageId) {
        return Optional.ofNullable(messageIndex.get(messageId));
    }
}
