package ru.myasnikov.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class CsvTest {
    @Test void keepsDecimalPrecisionDatePrecisionUnicodeAndColumnOrder() {
        assertEquals("SPARE-1;Деталь 1;Описание;BRAKE;AVAILABLE;15.9900;3;2026-10-28T10:00:00.123456789\n",Csv.format(List.of(IntegrationTest.spare(1,"15.9900"))));
    }
    @Test void escapesSeparatorsQuotesAndNewlines() {
        var spare=new Spare("X","Имя;детали","Текст \"да\"\nстрока","TIRE","DAMAGED","0.01",0,"2026-10-28");
        assertEquals("X;\"Имя;детали\";\"Текст \"\"да\"\"\nстрока\";TIRE;DAMAGED;0.01;0;2026-10-28\n",Csv.format(List.of(spare)));
    }
    @Test void malformedSourceCannotBecomeIncompleteRecord() throws Exception {
        assertThrows(IllegalArgumentException.class,()->Spare.parse(new ObjectMapper().readTree("{\"spareCode\":\"X\"}")));
        assertThrows(IllegalArgumentException.class,()->IntegrationTest.spare(1,"-5"));
    }
}
