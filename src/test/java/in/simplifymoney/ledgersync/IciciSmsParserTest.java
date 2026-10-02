package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.*;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.IciciSmsParser;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class IciciSmsParserTest {

    private final IciciSmsParser parser = new IciciSmsParser();

    @Test
    void parsesV1Format() {
        String body = "Dear Customer, Acct XX9075 is debited with INR 333.33 on 04/07/2026 07:54. Info: SWIGGY. Avl Bal Rs.49,857.25";
        RawMessage msg = new RawMessage("m-1", "sms", "VM-ICICIB-T",
                OffsetDateTime.parse("2026-07-04T07:55:00+05:30"), "dev-1", body);

        assertTrue(parser.supports(msg));
        Optional<ParsedTxn> parsed = parser.parse(msg);
        assertTrue(parsed.isPresent());

        ParsedTxn txn = parsed.get();
        assertEquals("9075", txn.accountLast4());
        assertEquals(Direction.DEBIT, txn.direction());
        assertEquals(new BigDecimal("333.33"), txn.amount());
        assertEquals("SWIGGY", txn.merchant());
        assertEquals(new BigDecimal("49857.25"), txn.statedBalance());
    }

    @Test
    void parsesV2FormatCredit() {
        String body = "ICICI Bank Acct XX9075 Cr INR 1250.33 on 23-Jul-2026 16:52; INTEREST CREDIT ref no 424353460512. BalAvl Rs 52,846.30";
        RawMessage msg = new RawMessage("m-2", "sms", "VM-ICICIB-T",
                OffsetDateTime.parse("2026-07-23T16:53:00+05:30"), "dev-1", body);

        assertTrue(parser.supports(msg));
        Optional<ParsedTxn> parsed = parser.parse(msg);
        assertTrue(parsed.isPresent());

        ParsedTxn txn = parsed.get();
        assertEquals("9075", txn.accountLast4());
        assertEquals(Direction.CREDIT, txn.direction());
        assertEquals(new BigDecimal("1250.33"), txn.amount());
        assertEquals("INTEREST CREDIT", txn.merchant());
        assertEquals(new BigDecimal("52846.30"), txn.statedBalance());
    }

    @Test
    void parsesV2FormatDebitWithWholeRupees() {
        String body = "ICICI Bank Acct XX9075 Dr INR 5 on 23-Jul-2026 18:41; UPI/BARBER ref no 154245459403. BalAvl Rs 52,841.30";
        RawMessage msg = new RawMessage("m-3", "sms", "VM-ICICIB-T",
                OffsetDateTime.parse("2026-07-23T18:42:00+05:30"), "dev-1", body);

        assertTrue(parser.supports(msg));
        Optional<ParsedTxn> parsed = parser.parse(msg);
        assertTrue(parsed.isPresent());

        ParsedTxn txn = parsed.get();
        assertEquals("9075", txn.accountLast4());
        assertEquals(Direction.DEBIT, txn.direction());
        assertEquals(new BigDecimal("5.00"), txn.amount());
        assertEquals("UPI/BARBER", txn.merchant());
        assertEquals(new BigDecimal("52841.30"), txn.statedBalance());
    }

    @Test
    void skipsLoanPromotionalMessage() {
        String body = "Get a pre-approved Personal Loan of upto Rs.5,00,000 at 10.5% p.a. Click to know more. T&C apply. -ICICI Bank";
        RawMessage msg = new RawMessage("m-4", "sms", "VM-ICICIB-T",
                OffsetDateTime.now(), "dev-1", body);

        assertTrue(parser.supports(msg));
        Optional<ParsedTxn> parsed = parser.parse(msg);
        assertTrue(parsed.isEmpty());
    }
}
