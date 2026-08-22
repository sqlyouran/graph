package com.looptrip;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class LoopTripApplicationTests {

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private ConstraintProperties constraintProperties;

    @Test
    void contextLoadsWithChatClientBuilder() {
        assertThat(chatClientBuilder).isNotNull();
    }

    @Test
    void bindsConstraintThresholdsFromApplicationYaml() {
        assertThat(constraintProperties.route().maxCrossAreaPerDay()).isEqualTo(2);
        assertThat(constraintProperties.route().minTransferMinutes()).isEqualTo(40);
        assertThat(constraintProperties.pace().minAttractionsPerDay()).isEqualTo(2);
        assertThat(constraintProperties.pace().maxAttractionsPerDay()).isEqualTo(4);
        assertThat(constraintProperties.pace().maxActivityMinutesPerDay()).isEqualTo(540);
    }
}
