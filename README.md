# EnderVault

EnderVault는 개인 서버의 파일 저장소를 웹에서 관리하기 위한 self-hosted NAS 프로젝트입니다.

Spring Boot 기반 backend와 React, TypeScript, Vite 기반 frontend로 구성되어 있으며, 파일 관리와 미리보기, 편집, 공유, 업로드, 원격 다운로드, 개인 메모, 세션 관리 등의 기능을 제공합니다.

프로젝트는 개인 서버의 단일 관리자 환경을 가정합니다. 다중 사용자 SaaS나 공개형 파일 호스팅 서비스를 목표로 하지 않습니다.

---

## Project Status

EnderVault는 현재 개발 단계입니다.

기능과 내부 구조가 지속적으로 변경되고 있으며, 정식 릴리스 이전에는 설정 파일이나 내부 metadata 구조가 별도의 migration 없이 변경될 수 있습니다.

Beta build 업데이트에 필요한 백업 항목은 [Updating Beta Builds](#updating-beta-builds)를 참고하세요.

---

## Features

### File Management

- 파일 및 디렉토리 탐색, 생성, 검색
- 이동, 복사, 이름 변경, 삭제
- Table / Grid 보기
- 정렬
- 휴지통
- 최근 항목 및 즐겨찾기
- 숨김 파일 정책
- 모바일 read-only mode

### Upload & Download

- tus 기반 resumable upload와 설정된 보존 기간 내 중단 session 재개
- 일반 파일 Range download
- 다중 파일 및 디렉토리 ZIP download
- 파일 크기, 총용량, 개수, 확장자, 만료 및 uploader 이름 정책을 링크별로 설정할 수 있는 File Request
- staging file 및 commit journal 기반 복구

Resumable upload session의 기본 보존 기간은 24시간입니다. 여러 항목이나 디렉토리를 즉석에서 묶는 ZIP download는 streaming response이므로 Range request와 중단 후 재개를 지원하지 않습니다.

### Preview & Editing

- 이미지
- 영상
- 음성
- 텍스트 및 source code
- Markdown
- PDF
- CBZ

영상, PDF, CBZ 파일에 대해서는 thumbnail cache를 생성합니다.

텍스트 파일은 CodeMirror 기반 editor를 제공하며, server-side draft 복구를 지원합니다.

### Archive

- 압축파일 내부 항목 탐색
- 지원 형식 압축 해제
- 선택한 파일과 디렉토리의 ZIP 생성

### Sharing

- 파일 및 디렉토리 공유 링크
- 링크별 preview 정책
- 링크별 만료 및 revoke 정책

### Remote Download

- URL 기반 서버 측 다운로드
- 브라우저에서 복사한 cURL GET 요청 파싱
- Range 기반 parallel download
- Direct / VPN outbound route 선택
- 작업별 route override

Remote Download는 기본적으로 비활성화되어 있습니다.

### Personal Tools

- 페이지 및 파일 context 기반 Sticky Note
- Bookmark 대상 페이지의 title 자동 완성 및 favicon 조회·캐시
- Activity Log
- 전역 작업 및 decision notification
- 선택적 Telegram notification

### Authentication & Administration

- Password login
- Passkey
- 활성 session 관리
- 동시 session 정책
- 관리자용 Settings UI
- storage 및 metadata consistency check

---

## Architecture

EnderVault는 Spring Boot backend와 React SPA를 중심으로 구성됩니다.

```text
Browser
   │
   ▼
React / TypeScript
   │
   ▼
Spring Boot
   │
   ├── Storage
   ├── Metadata
   ├── Upload / Download
   ├── Sharing
   └── Authentication
```

로그인 이후 주요 관리자 화면은 하나의 React SPA shell을 공유합니다.

Files, Dashboard, Settings 등의 화면을 이동할 때 전체 페이지를 다시 불러오지 않으므로 진행 중인 upload와 global task 상태가 유지됩니다.

로그인 페이지와 일부 public page에는 Thymeleaf를 사용합니다.

---

## Requirements

### Standalone JAR

- Java 21

### Browser

- JavaScript가 활성화된 최신 desktop 또는 mobile browser
- no-JS 환경은 지원하지 않음

### Source Build

저장소에 포함된 Maven Wrapper를 사용할 수 있습니다.

별도의 Maven 또는 Node.js 설치는 필수가 아닙니다.

### Docker

- Docker Engine
- Docker Compose plugin

Docker 배포에서는 host에 Java나 Maven을 별도로 설치할 필요가 없습니다.

---

## Getting Started

### Clone

```bash
git clone https://github.com/fourilla/EnderVault.git
cd EnderVault
```

---

### Standalone JAR

#### Build

Windows:

```powershell
.\mvnw.cmd clean package
```

Linux / macOS:

```bash
./mvnw clean package
```

빌드 결과:

```text
target/endervault-nas-0.1.0-SNAPSHOT.jar
```

Frontend build도 Maven build 과정에 포함됩니다.

최초 build에서는 Maven 및 npm dependency 다운로드를 위한 인터넷 연결이 필요합니다.

---

#### First Run

처음 실행하면 EnderVault는 현재 실행 위치에 기본 설정 파일을 생성한 뒤 종료합니다.

```bash
java -jar target/endervault-nas-0.1.0-SNAPSHOT.jar
```

생성된 파일:

```text
endervault-nas.properties
```

최소한 다음 항목을 확인합니다.

```properties
nas.setup.accepted=true
nas.storage.root=/path/to/your/storage
```

설정 후 다시 실행합니다.

```bash
java -jar target/endervault-nas-0.1.0-SNAPSHOT.jar
```

기본 주소:

```text
http://localhost:8080
```

초기 관리자 계정:

```text
ID : admin
PW : change-me
```

초기 계정은 최초 설정을 위한 계정입니다.

실제 사용 전 **Settings에서 관리자 비밀번호를 반드시 변경해야 합니다.**

변경된 비밀번호는 평문이 아닌 password hash 형태로 `endervault-nas.properties`에 저장됩니다.

---

## Docker Compose

Docker Compose를 이용하면 host에 Java나 Maven을 설치하지 않고 EnderVault를 build 및 실행할 수 있습니다.

저장소에는 기본 환경 설정 예시인 `.env.example`이 포함되어 있습니다.

실제 `.env` 파일은 직접 생성해야 하며 Git에서 제외됩니다.

### Prepare Environment

Windows PowerShell:

```powershell
Copy-Item .env.example .env
New-Item -ItemType Directory -Force docker/runtime/state, docker/runtime/storage
```

Linux / macOS:

```bash
cp .env.example .env
mkdir -p docker/runtime/state docker/runtime/storage
```

Linux에서는 `.env`의 다음 값을 실행 계정에 맞게 설정하는 것을 권장합니다.

```text
ENDERVAULT_UID
ENDERVAULT_GID
```

현재 값은 다음 명령으로 확인할 수 있습니다.

```bash
id -u
id -g
```

설정한 계정이 `ENDERVAULT_STATE_DIR`와 `ENDERVAULT_STORAGE_PATH`에 지정한 두 bind mount 디렉토리를 읽고 쓸 수 있는지도 확인해야 합니다.

---

### Storage Layout

기본 Docker 구성은 다음 두 경로를 사용합니다.

```text
docker/runtime/state
docker/runtime/storage
```

역할은 다음과 같습니다.

```text
state
 ├── endervault-nas.properties
 └── application logs

storage
 ├── managed files
 └── .endervault metadata
```

실제 위치는 `.env`에서 변경할 수 있습니다.

```text
ENDERVAULT_STATE_DIR
ENDERVAULT_STORAGE_PATH
```

---

### First Run

초기 설정 파일을 생성합니다.

```bash
docker compose run --rm --build endervault
```

기본 `.env`를 사용하는 경우 다음 위치에 생성된 설정 파일을 확인합니다.

```text
docker/runtime/state/endervault-nas.properties
```

`ENDERVAULT_STATE_DIR`를 변경했다면 설정 파일은 변경한 디렉토리 아래에 생성됩니다.

최소한 다음 항목을 변경합니다.

```properties
nas.setup.accepted=true
```

Docker 환경에서는 container 내부 storage root가 `/storage`로 설정됩니다.

실제 host storage 위치는 `.env`의 다음 값으로 지정합니다.

```text
ENDERVAULT_STORAGE_PATH
```

---

### Start

```bash
docker compose up -d
```

기본 설정에서는 host의 다음 주소에만 bind됩니다.

```text
127.0.0.1:8080
```

LAN 또는 reverse proxy를 통해 접근하려면 `.env`의 다음 값을 운영 환경에 맞게 변경합니다.

```text
ENDERVAULT_BIND_ADDRESS
```

---

## Optional VPN Egress

EnderVault는 선택적인 OpenVPN outbound routing을 지원합니다.

VPN 기능은 Gluetun을 사용하며 기본 `compose.yaml`과 별도의 Compose overlay로 분리되어 있습니다.

VPN을 사용하지 않는 환경에서는 관련 container, permission, secret 설정이 적용되지 않습니다.

실행 예:

```bash
docker compose -f compose.yaml -f compose.vpn.yaml --profile vpn up -d
```

VPN 환경에서는 관리자 UI에서 관리되는 outbound request의 기본 route를 Direct 또는 VPN으로 선택할 수 있습니다.

Remote Download는 각 작업별로 별도의 route를 지정할 수 있습니다.

VPN이 사용 가능하지 않은 상태에서는 VPN으로 지정된 요청을 자동으로 Direct route로 우회하지 않습니다.

상세한 VPN 설정은 다음 문서를 참고하세요.

[`docker/vpn/README.md`](docker/vpn/README.md)

---

## Configuration

EnderVault의 주요 설정은 다음 파일에서 관리합니다.

```text
endervault-nas.properties
```

설정 template:

```text
src/main/resources/endervault-nas.properties.template
```

설정 파일에는 다음과 같은 민감한 정보가 포함될 수 있습니다.

- 관리자 credential
- Telegram bot token
- storage path
- 외부 서비스 설정

실제 설정 파일은 공개하거나 Git repository에 commit하지 않는 것을 권장합니다.

---

### Passkey

Passkey를 실제 domain 환경에서 사용할 경우 HTTPS와 올바른 WebAuthn RP 설정이 필요합니다.

예:

```properties
nas.passkeys.rp-id=example.com
nas.passkeys.allowed-origins=https://example.com
```

---

### Remote Download

Remote Download는 server가 사용자가 입력한 외부 URL에 직접 연결하는 기능입니다.

보안상 기본값은 비활성화되어 있으며, 필요한 경우에만 활성화하는 것을 권장합니다.

URL 검사, redirect 검증 및 private network 접근 제한 등의 방어가 적용되어 있지만, 운영자는 해당 기능이 server-side outbound connection을 발생시킨다는 점을 고려해야 합니다.

Inspect는 파일 정보와 Range 지원을 확인하기 위해 실제 `GET` request인 `Range: bytes=0-0`을 전송합니다. 일회성 또는 download 횟수가 제한된 URL은 Inspect 단계에서 소비될 수 있습니다. 이런 URL에는 Advanced Request Options의 `Skip inspection`을 사용할 수 있지만, 파일명, 크기, 형식과 최종 URL을 미리 확인할 수 없으며 single-connection download만 허용됩니다.

---

### Share Policy

공유 링크의 preview 설정은 새 링크 생성 시 기본값으로 적용됩니다.

각 링크의 정책은 생성 시 별도로 저장되므로 이후 전역 설정을 변경해도 기존 공유 링크의 설정은 변경되지 않습니다.

초기 기본값은 만료 없음(`Never`)입니다. 링크를 생성할 때 만료 기간을 지정할 수 있으며, 생성된 링크는 관리자 UI에서 revoke할 수 있습니다.

---

## Updating Beta Builds

Beta 기간에는 설정과 metadata 구조가 변경될 수 있습니다.

업데이트 전에 다음 항목을 백업하는 것을 권장합니다.

```text
Managed Storage
endervault-nas.properties
.endervault/
```

기존 설정 파일에 존재하지 않는 새 configuration key는 application default가 적용됩니다.

업데이트 후에는 최신 template과 기존 설정을 비교해 새 설정값이 의도한 정책과 일치하는지 확인하는 것을 권장합니다.

```text
src/main/resources/endervault-nas.properties.template
```

---

## Deployment Recommendations

EnderVault는 **신뢰할 수 있는 관리자가 운영하는 개인용 NAS**를 전제로 설계되었습니다. Application 자체가 host 및 network security를 대체하지 않으므로 실제 서버에 배포할 경우 다음 구성을 권장합니다.

- 초기 관리자 비밀번호 변경
- 실제 NAS storage path 설정
- 설정 파일과 `.env` 외부 노출 방지
- HTTPS reverse proxy 사용
- firewall을 통한 접근 범위 제한
- Passkey 사용 시 RP ID와 Origin 확인
- Remote Download는 필요한 경우에만 활성화
- 중요한 데이터의 별도 backup 유지

Reverse proxy를 사용하는 경우 request body limit을 `nas.upload.resumable-chunk-size-bytes`보다 크게 설정해야 합니다. 대용량 upload를 위해 `/api/v1/uploads/` 경로의 request buffering과 timeout 정책도 함께 확인하세요.

EnderVault 자체는 저장된 사용자 파일을 암호화하지 않습니다. 저장장치 암호화가 필요한 경우 host의 disk 또는 filesystem encryption을 사용해야 합니다.
