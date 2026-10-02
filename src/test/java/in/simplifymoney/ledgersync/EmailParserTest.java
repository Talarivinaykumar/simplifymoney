package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.*;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.EmailParser;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EmailParserTest {

    private final EmailParser parser = new EmailParser();

    @Test
    void parsesHdfcCreditEmail() {
        String body = """
                Date: Wed, 01 Jul 2026 09:02:00 +0530
                Subject: Transaction alert on your account

                Dear Customer,

                Your account ending 4821 has been credited with INR 45,000.
                Merchant / Remarks: SALARY CREDIT
                Transaction reference: 1597155421

                This is a system generated email.
                """;

        RawMessage msg = new RawMessage("m-1", "email", "alerts@hdfcbank.net",
                OffsetDateTime.parse("2026-07-01T09:03:00+05:30"), "dev-1", body);

        assertTrue(parser.supports(msg));
        Optional<ParsedTxn> parsed = parser.parse(msg);
        assertTrue(parsed.isPresent());

        ParsedTxn txn = parsed.get();
        assertEquals("4821", txn.accountLast4());
        assertEquals(Direction.CREDIT, txn.direction());
        assertEquals(new BigDecimal("45000.00"), txn.amount());
        assertEquals("SALARY CREDIT", txn.merchant());
        assertEquals("m-1", txn.sourceMessageId());
        assertEquals(OffsetDateTime.parse("2026-07-01T09:02:00+05:30"), txn.occurredAt());
    }

    @Test
    void parsesIciciDebitEmail() {
        String body = """
                Date: Mon, 06 Jul 2026 11:25:00 +0530
                Subject: Transaction alert on your account

                Dear Customer,

                Your account ending 9075 has been debited with Rs.129.67.
                Merchant / Remarks: RELIANCE SMART
                Transaction reference: 6576810104

                This is a system generated email.
                """;

        RawMessage msg = new RawMessage("m-2", "email", "alerts@icicibank.com",
                OffsetDateTime.parse("2026-07-06T11:27:00+05:30"), "dev-1", body);

        assertTrue(parser.supports(msg));
        Optional<ParsedTxn> parsed = parser.parse(msg);
        assertTrue(parsed.isPresent());

        ParsedTxn txn = parsed.get();
        assertEquals("9075", txn.accountLast4());
        assertEquals(Direction.DEBIT, txn.direction());
        assertEquals(new BigDecimal("129.67"), txn.amount());
        assertEquals("RELIANCE SMART", txn.merchant());
    }

    @Test
    void ignoresNonEmailChannel() {
        RawMessage msg = new RawMessage("m-3", "sms", "alerts@hdfcbank.net",
                OffsetDateTime.now(), "dev-1", "Some text");
        assertFalse(parser.supports(msg));
    }
}
