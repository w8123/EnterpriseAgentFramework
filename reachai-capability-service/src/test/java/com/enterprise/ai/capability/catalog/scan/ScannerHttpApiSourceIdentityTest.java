package com.enterprise.ai.capability.catalog.scan;

import com.enterprise.ai.capability.catalog.httpapi.HttpApiSourceKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScannerHttpApiSourceIdentityTest {
    @Test
    void controllerUsesParsedFileClassAndMethodRatherThanSuffixGuessing() {
        String source = "src/main/java/com/example/OrdersController.java#com.example.OrdersController#find(long)";
        assertTrue(ScannerHttpApiSourceIdentity.matches(HttpApiSourceKind.CONTROLLER_SCAN,
                "OrdersController.java#OrdersController#find", source));
        assertFalse(ScannerHttpApiSourceIdentity.matches(HttpApiSourceKind.CONTROLLER_SCAN,
                "OtherController.java#OrdersController#find", source));
        assertFalse(ScannerHttpApiSourceIdentity.matches(HttpApiSourceKind.CONTROLLER_SCAN,
                "OrdersController.java#OtherController#find", source));
        assertFalse(ScannerHttpApiSourceIdentity.matches(HttpApiSourceKind.CONTROLLER_SCAN,
                "OrdersController.java#OrdersController#find(String)", source));
        assertFalse(ScannerHttpApiSourceIdentity.matches(HttpApiSourceKind.CONTROLLER_SCAN,
                "other/OrdersController.java#com.example.OrdersController#find(long)", source));
        assertFalse(ScannerHttpApiSourceIdentity.matches(HttpApiSourceKind.CONTROLLER_SCAN,
                "OrdersController#find", "com.example.OrdersController#find"));
        assertFalse(ScannerHttpApiSourceIdentity.matches(HttpApiSourceKind.STARTER_MVC,
                "OrdersController.java#OrdersController#find", source));
    }

    @Test
    void openApiRequiresExactPointerAndPreservesDirectoryInformationWhenPresent() {
        String source = "contracts/orders.yaml#/paths/~1orders~1{id}/get";
        assertTrue(ScannerHttpApiSourceIdentity.matches(HttpApiSourceKind.OPENAPI_SCAN,
                "orders.yaml#/paths/~1orders~1{id}/get", source));
        assertFalse(ScannerHttpApiSourceIdentity.matches(HttpApiSourceKind.OPENAPI_SCAN,
                "orders.yaml#/paths/~1orders~1{id}/post", source));
        assertFalse(ScannerHttpApiSourceIdentity.matches(HttpApiSourceKind.OPENAPI_SCAN,
                "other/orders.yaml#/paths/~1orders~1{id}/get", source));
        assertFalse(ScannerHttpApiSourceIdentity.matches(HttpApiSourceKind.OPENAPI_SCAN,
                "orders.yaml#find", "contracts/orders.yaml#find"));
    }
}
