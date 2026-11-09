package com.example.collections;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;

class EntryValidationTest {
    private static Validator validator;
    private static AutoCloseable factory;

    @BeforeAll static void createValidator() {
        var validationFactory=Validation.buildDefaultValidatorFactory();
        validator=validationFactory.getValidator();
        factory=validationFactory::close;
    }

    @AfterAll static void closeValidator() throws Exception {
        factory.close();
    }

    @Test void acceptsTrackingNumberCaseInsensitivelyAndWholePositiveAmount() {
        EntryRequest request=new EntryRequest(LocalDate.of(2026,1,1),"g240712-2665","2270005999848","1250000");
        assertTrue(validator.validate(request).isEmpty());
    }

    @Test void rejectsInvalidTrackingNumberPrnAndZeroAmount() {
        EntryRequest request=new EntryRequest(LocalDate.of(2026,1,1),"G123-456","1234","0");
        assertEquals(3,validator.validate(request).size());
    }

    @Test void rejectsFractionalAmount() {
        EntryRequest request=new EntryRequest(LocalDate.of(2026,1,1),
                "G240712-2665","2270005999848","1.25");
        assertEquals(1,validator.validate(request).size());
    }
}
