package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.*;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class IngestServiceTest {

    @Test
    void ingestsCorpusAWithExpectedTotals() throws Exception {
        InMemoryLedgerStore store = new InMemoryLedgerStore();
        IngestService ingest = new IngestService(new Parsers(), store);
        IngestService.Stats stats = ingest.ingestFile(Path.of("fixtures/corpus-a.jsonl"));

        assertEquals(522, stats.messagesRead());
        assertEquals(256, stats.transactionsWritten());
        assertEquals(43, stats.messagesSkipped());

        List<NormalizedTxn> txns = store.all();
        assertEquals(256, txns.size());

        // Account 9075 checks
        List<NormalizedTxn> txns9075 = txns.stream().filter(t -> "9075".equals(t.accountLast4())).toList();
        assertEquals(91, txns9075.size());

        BigDecimal spend9075 = txns9075.stream().filter(t -> t.category() == Category.SPEND)
                .map(NormalizedTxn::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("39058.11"), spend9075);

        BigDecimal income9075 = txns9075.stream().filter(t -> t.category() == Category.INCOME)
                .map(NormalizedTxn::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("41450.33"), income9075);

        List<NormalizedTxn> micro9075 = txns9075.stream().filter(t -> t.category() == Category.MICRO).toList();
        assertEquals(45, micro9075.size());
        BigDecimal microTotal9075 = micro9075.stream().map(NormalizedTxn::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("2086.34"), microTotal9075);

        BigDecimal transferOut9075 = txns9075.stream().filter(t -> t.category() == Category.TRANSFER && t.direction() == Direction.DEBIT)
                .map(NormalizedTxn::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("6000.00"), transferOut9075);

        BigDecimal transferIn9075 = txns9075.stream().filter(t -> t.category() == Category.TRANSFER && t.direction() == Direction.CREDIT)
                .map(NormalizedTxn::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("25000.00"), transferIn9075);

        // Account 4821 checks
        List<NormalizedTxn> txns4821 = txns.stream().filter(t -> "4821".equals(t.accountLast4())).toList();
        assertEquals(145, txns4821.size()); // 145 evidenced in corpus messages

        BigDecimal income4821 = txns4821.stream().filter(t -> t.category() == Category.INCOME)
                .map(NormalizedTxn::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("101340.83"), income4821);

        List<NormalizedTxn> micro4821 = txns4821.stream().filter(t -> t.category() == Category.MICRO).toList();
        assertEquals(52, micro4821.size());
        BigDecimal microTotal4821 = micro4821.stream().map(NormalizedTxn::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("2357.51"), microTotal4821);

        BigDecimal transferOut4821 = txns4821.stream().filter(t -> t.category() == Category.TRANSFER && t.direction() == Direction.DEBIT)
                .map(NormalizedTxn::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("25000.00"), transferOut4821);

        BigDecimal transferIn4821 = txns4821.stream().filter(t -> t.category() == Category.TRANSFER && t.direction() == Direction.CREDIT)
                .map(NormalizedTxn::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("6000.00"), transferIn4821);

        // Account 3310 (credit card) checks
        List<NormalizedTxn> txns3310 = txns.stream().filter(t -> "3310".equals(t.accountLast4())).toList();
        assertEquals(20, txns3310.size());
    }
}
