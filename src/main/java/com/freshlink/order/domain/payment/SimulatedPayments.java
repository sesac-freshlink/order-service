package com.freshlink.order.domain.payment;

/**
 * logistics-simulator가 만든 데모 주문의 결제 표시 (3차 할 일 1-3).
 * payment_key가 SIM-으로 시작하면 토스를 거치지 않은 결제라, 환불·조회 때도 토스를 부르지 않는다
 */
public final class SimulatedPayments {

    public static final String KEY_PREFIX = "SIM-";
    /** 화면에 보이는 결제 수단. 토스 method 값과 같은 모양 */
    public static final String METHOD = "카드";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_CANCELED = "CANCELED";

    private SimulatedPayments() {
    }

    public static boolean isSimulated(String paymentKey) {
        return paymentKey != null && paymentKey.startsWith(KEY_PREFIX);
    }
}
