package com.example.digitalsignchecker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = "app.api-key=test-key")
@Import(TestcontainersConfiguration.class)
class DigitalSignCheckerApplicationTests {

    @Test
    void contextLoads() {
    }

}
