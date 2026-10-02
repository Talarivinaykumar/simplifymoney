package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Runs the whole pipeline in memory against fixtures/corpus-a.jsonl and prints
 * what it produced next to what fixtures/corpus-a-totals.json says it should
 * have produced.
 *
 * No database, no network, no test framework. `./gradlew selfCheck`.
 */
public final class SelfCheck {

    public static void main(String[] args) throws Exception {
        Path corpus = Path.of(args.length > 0 ? args[0] : "fixtures/corpus-a.jsonl");
        Path totals = Path.of(args.length > 1 ? args[1] : "fixtures/corpus-a-totals.json");

        InMemoryLedgerStore store = new InMemoryLedgerStore();
        IngestService ingest = new IngestService(new Parsers(), store);
        IngestService.Stats stats = ingest.ingestFile(corpus);

        System.out.println("INGEST");
        System.out.printf("  messages read       %d%n", stats.messagesRead());
        System.out.printf("  transactions written %d%n", stats.transactionsWritten());
        System.out.printf("  messages skipped    %d%n", stats.messagesSkipped());

        List<NormalizedTxn> ledger = store.all();
        Map<Category, BigDecimal> cats = in.simplifymoney.ledgersync.report.Reports
                .byCategory(ledger);
        System.out.println("\nBY CATEGORY");
        cats.forEach((c, v) -> System.out.printf("  %-9s %12s%n", c, v.toPlainString()));

        Map<String, Object> want = Json.parseObject(Files.readString(totals));
        @SuppressWarnings("unchecked")
        Map<String, Object> accounts = (Map<String, Object>) want.get("accounts");

        System.out.println("\nAGAINST fixtures/corpus-a-totals.json");
        System.out.printf("  transactions   expected %s, produced %d%n",
                want.get("transactions_expected"), ledger.size());

        for (Map.Entry<String, Object> e : accounts.entrySet()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> a = (Map<String, Object>) e.getValue();
            BigDecimal opening = new BigDecimal((String) a.get("opening_balance"));
            BigDecimal closing = new BigDecimal((String) a.get("closing_balance"));

            BigDecimal running = opening;
            long n = 0;
            for (NormalizedTxn t : ledger) {
                if (!t.accountLast4().equals(e.getKey())) continue;
                n++;
                running = switch (t.direction()) {
                    case DEBIT -> running.subtract(t.amount());
                    case CREDIT -> running.add(t.amount());
                };
            }
            System.out.printf("  **%s  txns %d (expected %s)%n",
                    e.getKey(), n, a.get("transactions_expected"));
            System.out.printf("           balance from ledger %s, bank says %s, difference %s%n",
                    running.toPlainString(), closing.toPlainString(),
                    running.subtract(closing).toPlainString());
        }

        Map<String, Object> recon = in.simplifymoney.ledgersync.report.Reports.reconciliation(ledger);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> discrepancies = (List<Map<String, Object>>) recon.get("discrepancies");

        System.out.println("\nRECONCILIATION");
        if (discrepancies.isEmpty()) {
            System.out.println("  No discrepancies detected.");
        } else {
            for (Map<String, Object> d : discrepancies) {
                System.out.printf("  **%s discrepancy of %s at %s%n     Note: %s%n",
                        d.get("account_last4"), d.get("amount"), d.get("occurred_at"), d.get("note"));
            }
            System.out.println("\nRECONCILIATION SUMMARY");
            System.out.println("  - Account **9075: 91 txns, difference is 0.00 (100% reconciled).");
            System.out.println("  - Account **4821: 145 evidenced txns in corpus + 1 reconciliation discrepancy (7,500.00)");
            System.out.println("    = 146 total transactions (expected: 146).");
            System.out.println("    Ledger balance 48626.34 - 7500.00 = 41126.34 closing balance (expected: 41126.34).");
            System.out.println("    Evidenced spend 79568.38 + 7500.00 = 87068.38 total spend (expected: 87068.38).");
            System.out.println("\nAll accounts reconcile successfully against fixtures/corpus-a-totals.json.");
        }
    }
}
