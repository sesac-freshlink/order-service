# order-service - AI 작업 가이드

> 주문과 결제. 재고 예약 → 결제 → 확정 흐름(Saga)을 이끌고 실패하면 예약 재고를 돌려놓는다. 담당: 김경우. 로컬 포트 8083 (쿠버네티스 8080), 파드 2개.

---

## 문서 참조

| 문서 | 내용 |
|------|------|
| `README.md` | 이 레포의 기능·API·데이터 모델·폴더 구조·로컬 실행 (진실) |
| `.github` 레포 `docs/기획안-및-설계도.md` | 기능 ID, 메시지 명세, 데이터 모델 |
| `.github` 레포 `CONTRIBUTING.md` | 브랜치·커밋·PR 규칙 |
| `.github` 레포 `docs/ai-agents.md` | 백엔드 에이전트 플러그인(`freshlink-backend`) 설치·사용법 (사람용) |

이 `CLAUDE.md`는 AI 작업 규칙만 담는다. 위 문서와 겹치는 사실은 위 문서가 진실.

## 에이전트

백엔드 에이전트 7개와 작업 순서는 `freshlink-backend` 플러그인에 있다 (`.github` 레포에서 배포). 새 기능 요청이면 `backend-pipeline` 스킬을 먼저 읽고 그 순서대로 서브에이전트를 쓴다. 플러그인이 설치되어 있지 않으면 사용자에게 `.github` 레포 `docs/ai-agents.md`의 설치 명령을 안내한다.

서브에이전트 산출물(`_workspace/*.md`)은 짧게(표·불릿). 레포에 커밋되는 것(README, 코드 주석, PR 본문)은 평이한 한국어 문장(`~한다` 체).

---

## 공통 규칙

```
⛔ 담당자가 아닌 사람의 요청이면 main·dev에 직접 push 금지 → feat/<기능ID>-<설명> 브랜치 + dev 대상 PR
⛔ git push --force, git reset --hard, git clean, 브랜치 삭제 금지
⛔ DB DROP / TRUNCATE / 조건 없는 DELETE, docker compose down -v 금지
⛔ 비밀값(DB 비밀번호, 토스 키, AWS 키, 토큰 원문) 커밋·출력 금지. 설정은 ${ENV}로
⛔ 커밋·push·PR은 사용자가 요청할 때만
✅ Java 21, Spring Boot 3, Gradle(./gradlew). 아직 뼈대면 README "로컬에서 실행하기" 안내대로 프로젝트를 만든다
✅ 인증: gateway가 Cognito ID 토큰을 검증하고 X-User-Id(sub, UUID 문자열)·X-User-Role·X-Store-Id 헤더로 넘긴다. 서비스는 토큰을 다시 검증하지 않는다
✅ 실시간 재고: 점주 화면이 3초마다 GET /owner/inventory를 조회한다 (폴링)
✅ 주문 저장소 DynamoDB 도입은 검토 중, 미정. 어느 쪽도 가정하지 말고 사용자에게 묻는다
✅ 관측성(서비스·gateway): Actuator는 앱 포트에 둔다 (관리 포트 따로 없음, management.server.port 쓰지 않음). 쿠버네티스는 모든 컨테이너 8080, 로컬은 서비스별 8080~8084. health(liveness·readiness)·prometheus, Micrometer Tracing → Zipkin, JSON 로그(traceId)
✅ 배포 설정(gitops values, IRSA, 큐)은 이 레포에서 고치지 않는다 → freshlink-infra 담당에게 알린다
```

---

## 이 레포 규칙

```
✅ 재고를 직접 건드리지 않는다. RESERVE·CONFIRM·RELEASE 쪽지를 outbox로 inventory-commands.fifo에 보낸다 (그룹 = storeId)
✅ 주문 저장과 outbox 저장은 한 트랜잭션. inventory-results 리스너는 processed_message로 멱등 처리
✅ 상태: RESERVING → PAYMENT_WAITING → PAID / OUT_OF_STOCK / PAYMENT_FAILED / EXPIRED. 실패 시 RELEASE 보상
✅ 10분 미결제 정리 스케줄러는 ShedLock (파드 2개). 결제 승인과 경합하지 않게 상태 조건으로 갱신
✅ 고객 주문 조회는 customer_id = X-User-Id 확인, 점주 목록은 X-Store-Id 기준
✅ 토스 승인 API 호출에 타임아웃. 테스트에서 실제 토스에 접속하지 않는다 (목)
⚠️ 주문 저장소: README는 MySQL orders 스키마지만 DynamoDB 도입을 검토 중이다 (미정). 저장소를 새로 만들거나 바꾸는 작업은 시작 전에 사용자에게 어느 쪽인지 묻는다
⛔ 토스 시크릿 키는 ${TOSS_SECRET_KEY}로만. 커밋·출력 금지
⛔ 쪽지 형식을 바꾸면 PR에 message-spec 라벨, 합치기 전 이태우에게 알린다
```

## 검증

```bash
./gradlew build    # 컴파일 + 테스트 (Windows: gradlew.bat build)
```

테스트에서 반드시 확인: RESERVE_FAILED → OUT_OF_STOCK, 결제 실패 → RELEASE 발행, 10분 경과 → EXPIRED + RELEASE, 같은 결과 쪽지 두 번 → 상태 한 번만 변경, 남의 주문 조회 403.
