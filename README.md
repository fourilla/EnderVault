# EnderVault

EnderVault는 개인 서버의 특정 디렉토리를 웹 UI로 관리하기 위한 self-hosted NAS 프로젝트입니다.
Spring Boot, Thymeleaf, HTML, CSS, JavaScript를 기반으로 하며 파일 탐색, 업로드, 다운로드, 미리보기, 공유 링크, 휴지통, 즐겨찾기, 북마크, Passkey 로그인 등을 지원합니다.

이 프로젝트는 개인 서버에서 직접 운영하는 것을 목표로 합니다. 공개 서비스나 다중 사용자 SaaS를 목표로 한 프로젝트는 아닙니다.

## Features

- 파일/디렉토리 탐색, 업로드, 다운로드, 이동, 복사, 이름 변경
- 이미지, 영상, 텍스트, PDF, CBZ 미리보기
- 영상/CBZ 썸네일 캐시
- 선택한 파일 또는 디렉토리 공유 링크 발급
- 휴지통, 최근 항목, 즐겨찾기, 북마크
- Passkey 로그인
- Telegram 기반 activity 알림
- 모바일용 read-only mode
- 설정 파일 기반 운영

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
- `nas.storage.root`를 실제 NAS 저장소 경로로 변경
- `endervault-nas.properties`를 외부에 노출하지 않기
- HTTPS reverse proxy 사용
- Passkey 사용 시 도메인/RP 설정 확인
- Remote download는 보안 영향을 이해한 뒤 필요한 경우에만 활성화

EnderVault는 개인용 단일 관리자 NAS를 전제로 설계되었습니다. 인터넷에 직접 공개하기 전에는 HTTPS, 방화벽, 프록시 설정, 공유 링크 정책을 충분히 확인하세요.
