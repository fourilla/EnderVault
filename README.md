# EnderVault

EnderVault는 개인 서버의 특정 디렉토리를 웹 UI로 관리하기 위한 self-hosted NAS 프로젝트입니다.
Spring Boot, Thymeleaf, HTML, CSS, JavaScript를 기반으로 하며 파일 관리, 미리보기와 편집, 공유, 개인 메모, 관리자 세션 관리 등을 지원합니다.

이 프로젝트는 개인 서버에서 직접 운영하는 것을 목표로 합니다. 공개 서비스나 다중 사용자 SaaS를 목표로 한 프로젝트는 아닙니다.

## Features

- 파일/디렉토리 탐색, 생성, 검색, 업로드, 다운로드, 이동, 복사, 이름 변경
- table/grid 보기, 정렬, 페이징, 숨김 항목 정책과 모바일용 read-only mode
- 이미지, 영상, 텍스트, PDF, CBZ 미리보기와 영상/PDF/CBZ 썸네일 캐시
- CodeMirror 기반 텍스트 편집, 서버 draft 복구와 Markdown 미리보기
- 선택한 파일 또는 디렉토리 공유 링크 발급
- 휴지통, 최근 항목, 즐겨찾기와 원격 메타데이터를 지원하는 북마크
- 페이지와 파일 context에 대응되는 스티커 메모
- Passkey 로그인, 동시 세션 정책과 활성 세션 관리
- activity log, 백그라운드 task 표시와 선택적 Telegram 알림
- 메타데이터 정합성 검사 및 복구 도구
- URL 기반 Remote Download와 요청별 Direct/VPN 회선 선택
- Docker Compose 배포와 선택적 OpenVPN 아웃바운드 라우팅
- 설정 파일과 관리자 웹 UI 기반 운영

## Requirements

- Java 21
- Maven Wrapper 포함
- 별도 Maven 설치는 선택 사항입니다.

## Build

Windows:

```powershell
.\mvnw.cmd clean package
```

Linux/macOS:

```bash
./mvnw clean package
```

빌드 결과물은 다음 경로에 생성됩니다.

```text
target/endervault-nas-0.1.0-SNAPSHOT.jar
```

## First Run

처음 실행하면 EnderVault는 현재 실행 위치에 `endervault-nas.properties` 설정 파일을 생성하고 종료합니다.

```bash
java -jar target/endervault-nas-0.1.0-SNAPSHOT.jar
```

생성된 `endervault-nas.properties`를 열어 최소한 아래 값을 확인하고 수정하세요.

```properties
nas.setup.accepted=true
nas.storage.root=/path/to/your/storage
```

설정을 수정한 뒤 다시 실행합니다.

```bash
java -jar target/endervault-nas-0.1.0-SNAPSHOT.jar
```

기본 접속 주소는 다음과 같습니다.

```text
http://localhost:8080
```

기본 계정은 최초 접속용입니다.

```text
ID : admin
PW : change-me
```

실제 사용 전 반드시 웹 UI의 설정 페이지에서 관리자 비밀번호를 변경하세요.
웹 UI에서 변경한 비밀번호는 평문이 아니라 해시 형태로 `endervault-nas.properties`에 저장됩니다.

## Docker Compose

Docker Compose는 Java나 Maven을 호스트에 직접 설치하지 않고 EnderVault를 빌드하고 실행하는 선택 배포 방식입니다.

저장소에는 실제 `.env` 대신 민감한 값이 없는 `.env.example`이 포함됩니다. `.env`는 자동 생성되지 않으므로 clone 후 예시 파일을 복사하고 운영 환경에 맞게 수정하세요. 실제 `.env`는 Git에서 제외됩니다.

먼저 Docker 환경 설정을 준비하고 마운트할 디렉터리를 만듭니다.

Windows PowerShell:

```powershell
Copy-Item .env.example .env
New-Item -ItemType Directory -Force docker/runtime/state, docker/runtime/storage
```

Linux/macOS:

```bash
cp .env.example .env
mkdir -p docker/runtime/state docker/runtime/storage
```

Linux에서는 `.env`의 `ENDERVAULT_UID`, `ENDERVAULT_GID`를 `id -u`, `id -g` 결과와 맞추고, 두 마운트 경로에 해당 계정의 읽기/쓰기 권한이 있는지 확인하세요.

최초 설정 파일을 생성합니다.

```bash
docker compose run --rm --build endervault
```

`docker/runtime/state/endervault-nas.properties`를 검토하고 최소한 다음 값을 수정합니다.

```properties
nas.setup.accepted=true
```

Docker Compose는 컨테이너 내부의 storage root를 `/storage`로 고정합니다. 실제 호스트 저장소는 `.env`의 `ENDERVAULT_STORAGE_PATH`로 선택하며, properties의 `nas.storage.root`보다 Compose 환경변수가 우선합니다.

설정을 검토한 뒤 서비스를 실행합니다.

```bash
docker compose up -d
```

기본값은 호스트의 `127.0.0.1:8080`에만 공개됩니다. LAN에 직접 공개하거나 reverse proxy를 사용할 때는 `.env`의 `ENDERVAULT_BIND_ADDRESS`를 운영 환경에 맞게 변경하세요.

### Optional VPN Egress

선택한 아웃바운드 요청만 OpenVPN 회선으로 보낼 수 있도록 별도의 Gluetun Compose 프로필을 제공합니다. 기본 `compose.yaml`에는 VPN 권한이나 설정 파일 의존성이 없으며, VPN이 필요한 경우에만 오버레이를 함께 실행합니다.

```bash
docker compose -f compose.yaml -f compose.vpn.yaml --profile vpn up -d
```

VPN 회사와 관계없이 OpenVPN 프로필 한 개와 선택적 인증정보를 사용하는 Gluetun Custom 방식으로 통일되어 있습니다. 프로필 준비, 인증정보 secret, 초기 Direct DNS 조회, EnderVault proxy 설정과 현재 제한사항은 [`docker/vpn/README.md`](docker/vpn/README.md)를 확인하세요. 로그인 후 Topbar에서 서버 전체의 관리형 아웃바운드 요청을 Direct 또는 VPN으로 전환할 수 있습니다. Remote Download는 필요할 때 해당 작업만 Direct/VPN으로 예외 지정할 수 있으며, VPN이 준비되지 않은 상태에서는 Direct로 우회하지 않습니다.

VPN Compose overlay를 사용하면 `vpn:8888`, `vpn:9999`, `vpn:8000` 같은 컨테이너 내부 네트워크 주소도 overlay가 자동으로 주입합니다. Standalone JAR에서는 필요할 때 이 값을 properties에 직접 설정합니다.

## Configuration

주요 설정은 실행 위치의 `endervault-nas.properties`에서 관리합니다.
이 파일에는 로컬 저장소 경로, 관리자 계정, Telegram bot token 같은 민감한 값이 들어갈 수 있으므로 공유하거나 커밋하지 마세요.

설정 템플릿은 프로젝트 내부에 포함되어 있습니다.

```text
src/main/resources/endervault-nas.properties.template
```

Passkey를 실제 도메인에서 사용하려면 HTTPS 환경과 올바른 RP 설정이 필요합니다.

```properties
nas.passkeys.rp-id=example.com
nas.passkeys.allowed-origins=https://example.com
```

Remote download 기능은 보안상 기본 비활성화되어 있습니다.
이 기능을 활성화하면 서버가 사용자가 입력한 URL에 직접 접근하므로, 신뢰할 수 있는 환경에서만 사용하세요.

## Deployment Notes

개인 서버에서 사용할 때는 다음을 권장합니다.

- 관리자 비밀번호를 반드시 변경
- Standalone JAR에서는 `nas.storage.root`, Docker에서는 `.env`의 `ENDERVAULT_STORAGE_PATH`를 실제 NAS 저장소에 맞게 설정
- `endervault-nas.properties`를 외부에 노출하지 않기
- HTTPS reverse proxy 사용
- Passkey 사용 시 도메인/RP 설정 확인
- Remote download는 보안 영향을 이해한 뒤 필요한 경우에만 활성화

EnderVault는 개인용 단일 관리자 NAS를 전제로 설계되었습니다. 인터넷에 직접 공개하기 전에는 HTTPS, 방화벽, 프록시 설정, 공유 링크 정책을 충분히 확인하세요.
