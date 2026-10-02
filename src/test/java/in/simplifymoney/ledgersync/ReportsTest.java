package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.*;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.report.Reports;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ReportsTest {

    private static List<NormalizedTxn> ledger;

    @BeforeAll
    static void setUp() throws Exception {
        InMemoryLedgerStore store = new InMemoryLedgerStore();
        new IngestService(new Parsers(), store).ingestFile(Path.of("fixtures/corpus-a.jsonl"));
        ledger = store.all();
    }

    @Test
    void generatesValidSummary() {
        Map<String, Object> summary = Reports.summary(ledger);
        assertNotNull(summary);
        assertTrue(summary.containsKey("accounts"));

        @SuppressWarnings("unchecked")
        Map<String, Object> accounts = (Map<String, Object>) summary.get("accounts");
        assertTrue(accounts.containsKey("4821"));
        assertTrue(accounts.containsKey("9075"));
        assertTrue(accounts.containsKey("3310"));

        @SuppressWarnings("unchecked")
        Map<String, Object> a9075 = (Map<String, Object>) accounts.get("9075");
        assertEquals("39058.11", a9075.get("spend"));
        assertEquals("41450.33", a9075.get("income"));
        assertEquals(45, a9075.get("micro_count"));
        assertEquals("2086.34", a9075.get("micro_total"));
        assertEquals("6000.00", a9075.get("transferred_out"));
        assertEquals("25000.00", a9075.get("transferred_in"));

        @SuppressWarnings("unchecked")
        Map<String, Object> a4821 = (Map<String, Object>) accounts.get("4821");
        assertEquals("79568.38", a4821.get("spend"));
        assertEquals("101340.83", a4821.get("income"));
        assertEquals(52, a4821.get("micro_count"));
        assertEquals("2357.51", a4821.get("micro_total"));
        assertEquals("25000.00", a4821.get("transferred_out"));
        assertEquals("6000.00", a4821.get("transferred_in"));
    }

    @Test
    void generatesValidLedgerDocument() {
        Map<String, Object> doc = Reports.ledgerDocument(ledger);
        assertNotNull(doc);
        assertTrue(doc.containsKey("transactions"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> txns = (List<Map<String, Object>>) doc.get("transactions");
        assertEquals(256, txns.size());

        Map<String, Object> first = txns.get(0);
        assertTrue(first.containsKey("account_last4"));
        assertTrue(first.containsKey("occurred_at"));
        assertTrue(first.containsKey("direction"));
        assertTrue(first.containsKey("amount"));
        assertTrue(first.containsKey("category"));
        assertTrue(first.containsKey("merchant"));
        assertTrue(first.containsKey("source_message_ids"));
    }

    @Test
    void generatesReconciliationWithDiscrepancy() {
        Map<String, Object> recon = Reports.reconciliation(ledger);
        assertNotNull(recon);
        assertTrue(recon.containsKey("discrepancies"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) recon.get("discrepancies");
        assertFalse(list.isEmpty());

        Map<String, Object> d = list.get(0);
        assertEquals("4821", d.get("account_last4"));
        assertEquals("7500.00", d.get("amount"));
        assertNotNull(d.get("occurred_at"));
        assertNotNull(d.get("note"));
    }
}
