# glass-redis

Redis 서버를 Java로 직접 구현하고, **내부에서 벌어지는 일을 실시간으로 들여다보는** 프로젝트.

## 현재 상태

09/09 시작

## 왜 만드나

Redis, JAVA 밑단부터 복습 / 백엔드 실무 지식 쌓기

`SET key value EX 10` 을 치면 10초 뒤에 키가 사라진다는 건 알지만,
**누가 언제 그걸 지우는지**는 문서를 읽어도 잘 와닿지 않기에


1. `redis-cli`가 그대로 접속되는 Redis 호환 서버
2. 그 서버의 내부 상태를 실시간으로 그리는 대시보드

## 빌드 / 실행

Java 21 이상 필요

```bash
./gradlew build        # 빌드
./gradlew test         # 테스트
./gradlew run          # 실행
```

## 라이선스

MIT
