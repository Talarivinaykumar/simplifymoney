package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.*;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.store.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BackfillAndConsistencyTest {

    private Path tempDb;
    private SqlLedgerStore sqlStore;

    @BeforeEach
    void setUp() throws IOException {
        tempDb = Files.createTempFile("test_ledger_", ".db");
        sqlStore = new SqlLedgerStore(tempDb);
        sqlStore.migrate(Path.of("db", "migration"));
    }

    @AfterEach
    void tearDown() {
        if (sqlStore != null) {
            sqlStore.close();
        }
        try {
            Files.deleteIfExists(tempDb);
            Files.deleteIfExists(Path.of(tempDb.toString() + ".mv.db"));
        } catch (IOException ignored) {}
    }

    @Test
    void backfillMigratesSqlDataAndIsIdempotent() {
        InMemoryDocumentStore docStore = new InMemoryDocumentStore();
        Backfill backfill = new Backfill(sqlStore, docStore);

        // First run: reads 15 legacy rows from V2__seed.sql
        // Several rows in V2__seed.sql are duplicate Swiggy/Myntra/Chaiwala/BigBasket entries
        Backfill.Result result1 = backfill.run();
        assertEquals(15, result1.read());
        assertTrue(result1.written() < 15, "Duplicates should be merged");
        assertEquals(result1.read(), result1.written() + result1.skipped());

        // Second run: should be completely idempotent (0 written, all skipped)
        Backfill.Result result2 = backfill.run();
        assertEquals(15, result2.read());
        assertEquals(0, result2.written());
        assertEquals(15, result2.skipped());
    }

    @Test
    void consistencyCheckerPassesWhenStoresAgree() {
        InMemoryDocumentStore docStore = new InMemoryDocumentStore();
        new Backfill(sqlStore, docStore).run();

        ConsistencyChecker checker = new ConsistencyChecker(sqlStore, docStore);
        List<ConsistencyChecker.Divergence> divergences = checker.check();
        assertTrue(divergences.isEmpty(), "Stores should agree after clean backfill: " + divergences);
    }

    @Test
    void consistencyCheckerFindsDeliberateModifications() {
        InMemoryDocumentStore docStore = new InMemoryDocumentStore();
        new Backfill(sqlStore, docStore).run();

        // Deliberately alter a transaction in document store by adding an extra transaction
        NormalizedTxn altered = new NormalizedTxn(
                "4821",
                OffsetDateTime.parse("2026-06-25T10:00:00+05:30"),
                Direction.DEBIT,
                new BigDecimal("999.00"),
                Category.SPEND,
                "TAMPERED MERCHANT",
                List.of("m-tampered")
        );
        docStore.save(altered);

        ConsistencyChecker checker = new ConsistencyChecker(sqlStore, docStore);
        List<ConsistencyChecker.Divergence> divergences = checker.check();

        assertFalse(divergences.isEmpty(), "Checker must detect divergence");
        boolean foundExtra = divergences.stream().anyMatch(d -> d.what().contains("extra transaction") || d.what().contains("running total mismatch"));
        assertTrue(foundExtra, "Checker must report the alteration");
    }

    @Test
    void documentStoreServesThreeAccessPatterns() {
        InMemoryDocumentStore docStore = new InMemoryDocumentStore();
        NormalizedTxn t1 = new NormalizedTxn(
                "4821",
                OffsetDateTime.parse("2026-07-04T20:24:00+05:30"),
                Direction.DEBIT,
                new BigDecimal("2499.50"),
                Category.SPEND,
                "AMAZON PAY",
                List.of("m-1", "m-2")
        );
        docStore.save(t1);

        // Q1: forAccountMonth
        List<NormalizedTxn> q1 = docStore.forAccountMonth("4821", YearMonth.of(2026, 7));
        assertEquals(1, q1.size());
        assertEquals(t1, q1.get(0));

        // Q2: categoryTotals
        var totals = docStore.categoryTotals("4821");
        assertEquals(new BigDecimal("2499.50"), totals.get(Category.SPEND));
        assertEquals(new BigDecimal("0.00"), totals.get(Category.INCOME));

        // Q3: byMessageId
        assertTrue(docStore.byMessageId("m-1").isPresent());
        assertTrue(docStore.byMessageId("m-2").isPresent());
        assertTrue(docStore.byMessageId("m-unknown").isEmpty());
    }
}
