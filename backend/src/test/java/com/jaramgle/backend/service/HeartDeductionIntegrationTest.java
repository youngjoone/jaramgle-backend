package com.jaramgle.backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 간단한 예시 테스트: 실패 시 하트 차감이 일어나지 않아야 한다.
 * TODO: 실제 주문/스토리 생성 로직을 호출하도록 확장 필요.
 */
@SpringBootTest
@ActiveProfiles("local")
class HeartDeductionIntegrationTest {

    @Autowired
    private HeartWalletService heartWalletService;

    @Test
    @Transactional
    void failingOperationShouldNotDeductHeart() {
        // given
        Long userId = 1L;
        int before = heartWalletService.getBalance(userId);

        // when & then
        assertThatThrownBy(() -> {
            // TODO: 실패를 일으키는 스토리/스토리북 생성 호출로 교체
            heartWalletService.decreaseHeart(userId, 1);
            throw new RuntimeException("force failure");
        }).isInstanceOf(RuntimeException.class);

        int after = heartWalletService.getBalance(userId);
        // 기대: 롤백되어 차감되지 않는다
        assert after == before;
    }
}
