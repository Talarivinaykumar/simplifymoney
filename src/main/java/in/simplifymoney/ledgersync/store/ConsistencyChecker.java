package in.simplifymoney.ledgersync.store;

import java.util.List;

/**
 * Proves the two stores agree, and says precisely where they do not.
 *
 * NOT IMPLEMENTED - this is yours.
 *
 * We will run your checker against a document store we have deliberately
 * altered. It has to find what we changed and name it. A checker that only
 * compares row counts will not.
 */
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;

/**
 * Proves the two stores agree, and says precisely where they do not.
 *
 * Compares:
 *  - Every transaction and its fields (amount, direction, category, merchant, sourceMessageIds)
 *  - Message ID index lookups (byMessageId)
 *  - Category running totals per account
 *  - Extra/unexpected records in DocumentStore
 */
public final class ConsistencyChecker {

    private final SqlLedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(SqlLedgerStore sql, DocumentStore documents) {
        this.sql = sql;
        this.documents = documents;
    }

    public List<Divergence> check() {
        List<Divergence> divergences = new ArrayList<>();
        List<NormalizedTxn> sqlRows = sql.all();

        // 1. Deduplicate SQL rows to obtain canonical transactions
        record TxnKey(String accountLast4, String occurredAt, String direction, String amount) {}
        Map<TxnKey, NormalizedTxn> canonicalSql = new LinkedHashMap<>();
        Map<String, Set<YearMonth>> accountMonths = new LinkedHashMap<>();

        for (NormalizedTxn t : sqlRows) {
            TxnKey key = new TxnKey(
                    t.accountLast4(),
                    t.occurredAt().toString(),
                    t.direction().name(),
                    t.amount().setScale(2).toPlainString()
            );
            NormalizedTxn existing = canonicalSql.get(key);
            if (existing == null) {
                canonicalSql.put(key, t);
            } else {
                // Merge source message ids
                List<String> merged = new ArrayList<>(existing.sourceMessageIds());
                for (String id : t.sourceMessageIds()) {
                    if (!merged.contains(id)) merged.add(id);
                }
                merged.sort(String::compareTo);
                canonicalSql.put(key, new NormalizedTxn(
                        existing.accountLast4(),
                        existing.occurredAt(),
                        existing.direction(),
                        existing.amount(),
                        existing.category(),
                        existing.merchant(),
                        merged
                ));
            }
            accountMonths.computeIfAbsent(t.accountLast4(), k -> new HashSet<>())
                    .add(YearMonth.from(t.occurredAt()));
        }

        // 2. Verify all canonical SQL transactions exist in DocumentStore
        Set<String> verifiedDocKeys = new HashSet<>();

        for (NormalizedTxn sqlTxn : canonicalSql.values()) {
            YearMonth ym = YearMonth.from(sqlTxn.occurredAt());
            List<NormalizedTxn> docTxns = documents.forAccountMonth(sqlTxn.accountLast4(), ym);

            Optional<NormalizedTxn> match = docTxns.stream().filter(d ->
                    d.accountLast4().equals(sqlTxn.accountLast4())
                    && d.occurredAt().equals(sqlTxn.occurredAt())
                    && d.direction() == sqlTxn.direction()
                    && d.amount().compareTo(sqlTxn.amount()) == 0
            ).findFirst();

            String txnIdentifier = sqlTxn.accountLast4() + "@" + sqlTxn.occurredAt() + " [" + sqlTxn.amount() + "]";

            if (match.isEmpty()) {
                divergences.add(new Divergence(
                        "missing transaction in documents: " + txnIdentifier,
                        sqlTxn.toString(),
                        "null"
                ));
            } else {
                NormalizedTxn docTxn = match.get();
                verifiedDocKeys.add(docKey(docTxn));

                if (sqlTxn.category() != docTxn.category()) {
                    divergences.add(new Divergence(
                            "category mismatch for " + txnIdentifier,
                            sqlTxn.category().name(),
                            docTxn.category().name()
                    ));
                }
                if (!sqlTxn.merchant().equals(docTxn.merchant())) {
                    divergences.add(new Divergence(
                            "merchant mismatch for " + txnIdentifier,
                            sqlTxn.merchant(),
                            docTxn.merchant()
                    ));
                }
                if (sqlTxn.amount().compareTo(docTxn.amount()) != 0) {
                    divergences.add(new Divergence(
                            "amount mismatch for " + txnIdentifier,
                            sqlTxn.amount().toPlainString(),
                            docTxn.amount().toPlainString()
                    ));
                }
                if (!new HashSet<>(docTxn.sourceMessageIds()).containsAll(sqlTxn.sourceMessageIds())) {
                    divergences.add(new Divergence(
                            "sourceMessageIds mismatch for " + txnIdentifier,
                            sqlTxn.sourceMessageIds().toString(),
                            docTxn.sourceMessageIds().toString()
                    ));
                }
            }

            // 3. Verify message ID index lookups
            for (String msgId : sqlTxn.sourceMessageIds()) {
                Optional<NormalizedTxn> fromMsg = documents.byMessageId(msgId);
                if (fromMsg.isEmpty()) {
                    divergences.add(new Divergence(
                            "byMessageId missing mapping for message " + msgId,
                            txnIdentifier,
                            "null"
                    ));
                } else if (!matches(sqlTxn, fromMsg.get())) {
                    divergences.add(new Divergence(
                            "byMessageId returned different transaction for message " + msgId,
                            txnIdentifier,
                            fromMsg.get().toString()
                    ));
                }
            }
        }

        // 4. Verify running category totals per account
        Map<String, Map<Category, BigDecimal>> sqlTotalsByAcct = new HashMap<>();
        for (NormalizedTxn t : canonicalSql.values()) {
            Map<Category, BigDecimal> acctMap = sqlTotalsByAcct.computeIfAbsent(
                    t.accountLast4(), k -> new EnumMap<>(Category.class));
            BigDecimal cur = acctMap.getOrDefault(t.category(), BigDecimal.ZERO.setScale(2));
            acctMap.put(t.category(), cur.add(t.amount()));
        }

        for (Map.Entry<String, Map<Category, BigDecimal>> entry : sqlTotalsByAcct.entrySet()) {
            String acct = entry.getKey();
            Map<Category, BigDecimal> expectedTotals = entry.getValue();
            Map<Category, BigDecimal> docTotals = documents.categoryTotals(acct);

            for (Category c : Category.values()) {
                BigDecimal expected = expectedTotals.getOrDefault(c, BigDecimal.ZERO.setScale(2));
                BigDecimal actual = docTotals.getOrDefault(c, BigDecimal.ZERO.setScale(2));
                if (expected.compareTo(actual) != 0) {
                    divergences.add(new Divergence(
                            "running total mismatch for account " + acct + " category " + c.name(),
                            expected.toPlainString(),
                            actual.toPlainString()
                    ));
                }
            }
        }

        // 5. Check for extra transactions in DocumentStore
        for (Map.Entry<String, Set<YearMonth>> e : accountMonths.entrySet()) {
            String acct = e.getKey();
            for (YearMonth ym : e.getValue()) {
                List<NormalizedTxn> docList = documents.forAccountMonth(acct, ym);
                for (NormalizedTxn dt : docList) {
                    if (!verifiedDocKeys.contains(docKey(dt))) {
                        divergences.add(new Divergence(
                                "unexpected extra transaction in documents: " + acct + "@" + dt.occurredAt(),
                                "null",
                                dt.toString()
                        ));
                    }
                }
            }
        }

        return divergences;
    }

    private static boolean matches(NormalizedTxn a, NormalizedTxn b) {
        return a.accountLast4().equals(b.accountLast4())
                && a.occurredAt().equals(b.occurredAt())
                && a.direction() == b.direction()
                && a.amount().compareTo(b.amount()) == 0;
    }

    private static String docKey(NormalizedTxn t) {
        return t.accountLast4() + "#" + t.occurredAt() + "#" + t.direction() + "#" + t.amount().setScale(2).toPlainString();
    }

    /** One place the two stores disagree. */
    public record Divergence(String what, String inSql, String inDocuments) {}
}
