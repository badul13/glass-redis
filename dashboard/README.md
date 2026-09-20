# 대시보드

glass-redis 서버의 내부 상태를 그리는 화면. React + TypeScript, 번들러는 Vite.

## 빌드

따로 할 일은 없다. Node 가 설치돼 있으면 `./gradlew build` 가 `npm ci` 와 `npm run build` 를
알아서 돌리고, 결과(`dist/`)를 jar 안에 넣는다. 서버가 `http://127.0.0.1:8080` 에서 직접 내보낸다.

Node 가 없으면 이 디렉터리는 통째로 건너뛴다. 서버는 그대로 빌드되고 돌며, 그 주소에는
Node 를 설치하라는 안내 페이지가 뜬다.

## 화면을 고치는 중이라면

```bash
npm run dev        # http://localhost:5173
```

서버(`./gradlew run`)를 따로 띄워두면 된다. `/api` 요청은 Vite 가 8080 으로 넘겨주므로
화면 코드는 개발이든 배포든 똑같이 `/api/stream` 하나만 본다.

## 서버에서 오는 것

연결은 SSE 하나(`GET /api/stream`)뿐이고, 두 종류가 번갈아 온다.

| 이름 | 주기 | 내용 |
|---|---|---|
| `activity` | 최대 100ms | 그 사이 벌어진 일들(명령 실행, 접속, 키 삭제, 만료 샘플링)과 못 받고 버린 개수 |
| `keyspace` | 500ms | 지금 들어 있는 키와 남은 TTL |

JSON 의 모양은 Java 쪽 `DashboardJson` 이 만들고 `DashboardJsonTest` 가 못박아 둔다.
`src/types.ts` 는 그 모양을 그대로 옮겨 적은 것이라, 서버에서 필드 이름이 바뀌면 여기도 같이 고쳐야 한다.
