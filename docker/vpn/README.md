# 선택형 VPN 아웃바운드

EnderVault는 일부 관리형 아웃바운드 HTTP 요청을 비공개 Gluetun/OpenVPN
프록시를 통해 전송할 수 있습니다. NAS 웹 UI, 파일 응답, 로그인, Passkey,
Telegram 통신은 일반 네트워크를 그대로 사용합니다.

VPN 지원은 선택 기능입니다. `compose.yaml`만 사용하면 애플리케이션은 VPN
권한이나 VPN 인증정보 없이 실행됩니다.

## 지원하는 VPN 입력 형식

EnderVault는 VPN 제공자에 종속되지 않는 하나의 OpenVPN 경로를 사용합니다.
사용자가 Gluetun의 Native Provider를 별도로 선택할 필요는 없습니다.

다음 파일을 준비합니다.

```text
docker/secrets/vpn/config/custom.ovpn
.env
```

- 사용할 `.ovpn` 프로필을 `config` 디렉터리에 넣습니다.
- 프로필이 참조하는 인증서나 키 파일도 같은 디렉터리 아래에 두고, 프로필에는
  상대 경로를 사용합니다.
- 저장소 루트의 `.env` 파일에 `ENDERVAULT_OPENVPN_USER`와
  `ENDERVAULT_OPENVPN_PASSWORD`를 설정합니다.
- 사용자 이름과 비밀번호 인증을 사용하지 않는 프로필이라면 두 값을 비워둡니다.

`docker/secrets` 아래의 모든 파일은 Git과 Docker 빌드 컨텍스트에서
제외됩니다. OVPN 프로필에는 인증서나 개인 키가 포함될 수 있으므로 인증정보와
동일하게 취급해야 합니다.

Compose는 `.env`에서 OpenVPN 인증정보를 읽고 VPN 서비스의 `/run/secrets`
아래에 파일로만 제공합니다. 해당 값은 이미지에 복사되지 않으며 Gluetun 컨테이너
환경변수에도 직접 저장되지 않습니다. 다만 로컬 `.env` 파일 자체는 평문이므로
Git에 추가하지 말고 호스트 계정의 접근 권한도 제한해야 합니다.

## 환경 경로 설정

`.env.example`을 `.env`로 복사하고 필요에 따라 경로를 변경합니다.

```properties
ENDERVAULT_VPN_STATE_DIR=./docker/runtime/vpn
ENDERVAULT_VPN_CONFIG_DIR=./docker/secrets/vpn/config
ENDERVAULT_VPN_CONFIG_NAME=custom.ovpn

ENDERVAULT_OPENVPN_USER=
ENDERVAULT_OPENVPN_PASSWORD=
```

`ENDERVAULT_VPN_CONFIG_NAME`에는 경로가 아닌 단순한 `.ovpn` 또는 `.conf`
파일 이름만 사용할 수 있습니다. 하나의 VPN 서비스는 선택된 프로필 하나를
사용하지만, 프로필이 참조하는 다른 인증서 파일들은 같은 디렉터리에 함께 둘 수
있습니다.

## 프로필 준비 과정

Gluetun은 터널이 준비되기 전에 Custom 프로필의 VPN endpoint hostname을
의도적으로 해석하지 않습니다. EnderVault는 VPN 제공자와 무관한 설정 방식을
유지하기 위해 최초 한 번의 일반 회선 DNS 조회를 허용합니다.

Gluetun이 시작되기 전에 일회성 `vpn-config` 서비스가 다음 작업을 수행합니다.

1. 읽기 전용 secret 디렉터리에서 원본 프로필을 읽습니다.
2. 활성화된 모든 `remote` hostname을 컨테이너의 일반 DNS로 해석합니다.
3. 인증서와 키의 상대 경로를 읽기 전용 컨테이너 경로로 변경합니다.
4. 실행용 복사본을 `docker/runtime/vpn/generated/<profile-name>`에 원자적으로
   기록합니다.
5. 정상 종료하여 `vpn` 서비스가 시작될 수 있게 합니다.

원본 OVPN 파일은 수정하지 않습니다. Gluetun은 생성된 프로필을 읽지만 인증서와
키는 계속 읽기 전용 input mount에서 가져옵니다.

최초 DNS resolver와 네트워크 운영자는 VPN endpoint hostname이 조회됐다는
사실을 알 수 있습니다. 정상적인 OpenVPN 프로필은 CA와 인증서를 통해 VPN 서버를
검증하므로, DNS가 다른 주소를 반환하더라도 일반적으로 위조 서버에 연결되는 대신
연결에 실패합니다. 다만 DNS 조작은 서비스 거부, 검열 또는 오래된 endpoint 선택을
유발할 수 있습니다. 더 엄격한 resolver/bootstrap 정책은 향후 개선할 수 있습니다.

프로필 준비는 Compose 시작 시 실행되며 Gluetun 내부 프로세스가 자동 재시작될
때마다 실행되지는 않습니다. VPN 제공자가 endpoint 주소를 변경했거나 원본 프로필을
교체했다면 다음 실행 명령을 다시 사용하십시오.

```bash
docker compose -f compose.yaml -f compose.vpn.yaml --profile vpn up -d
```

## EnderVault 애플리케이션 설정

VPN Compose overlay는 컨테이너 내부 네트워크 구성을 소유하며, 다음 Spring
설정값을 자동으로 주입합니다.

```properties
nas.outbound.vpn.enabled=true
nas.outbound.vpn.proxy-host=vpn
nas.outbound.vpn.proxy-port=8888
nas.outbound.vpn.tunnel-health-url=http://vpn:9999/
nas.outbound.vpn.control-url=http://vpn:8000
nas.outbound.vpn.control-api-key-file=/var/run/endervault-vpn/auth/control-api.key
nas.outbound.vpn.profile-name=custom.ovpn
```

위 Docker 서비스 주소를 Standalone JAR 배포의 설정에 복사하면 안 됩니다. `vpn`
hostname은 비공개 Compose 네트워크 안에서만 존재합니다. 컨테이너를 재시작하면
Docker 환경변수 override가 `endervault-nas.properties`에 기록된 값보다 높은
우선순위로 적용됩니다.

다음과 같이 Compose 네트워크 구조와 무관한 동작 및 튜닝 설정은 계속 애플리케이션
properties에서 관리합니다.

```properties
nas.outbound.vpn.health-connect-timeout-ms=1500
nas.outbound.vpn.health-request-timeout-ms=3000
nas.outbound.vpn.health-check-interval-ms=30000

# 서버 시작 시 기본값입니다. Topbar에서 변경한 런타임 상태는 서버 메모리에만 저장됩니다.
nas.outbound.initial-route=direct
```

프록시와 health 포트는 비공개 Compose 네트워크 내부에만 공개됩니다. Gluetun
Control API 역시 같은 네트워크 안에서만 접근할 수 있으며, `vpn-config` 서비스가
생성한 API 키 인증이 필요합니다.

## 실행 방법

최종 Compose 설정이 올바른지 검사합니다.

```bash
docker compose \
  --env-file .env \
  -f compose.yaml \
  -f compose.vpn.yaml \
  --profile vpn \
  config --quiet
```

EnderVault와 VPN을 시작합니다.

```bash
docker compose -f compose.yaml -f compose.vpn.yaml --profile vpn up -d --build
```

프로필 준비 과정과 VPN 터널 로그를 확인합니다.

```bash
docker compose -f compose.yaml -f compose.vpn.yaml logs vpn-config vpn
```

동일한 Compose 프로젝트를 종료합니다.

```bash
docker compose -f compose.yaml -f compose.vpn.yaml --profile vpn down
```

## 런타임 정책

- Topbar에서 서버 전체의 관리형 아웃바운드 회선을 Direct 또는 VPN으로
  전환합니다.
- 북마크 메타데이터 작업은 시작하는 시점의 회선을 확정하여 사용합니다.
- Remote Download는 전역 회선을 따르거나 해당 작업에 한해 다른 회선을 명시할 수
  있습니다.
- 이미 실행 중인 작업은 작업을 생성할 때 선택된 회선을 계속 사용합니다.
- `VPN_REQUIRED` 요청은 프록시나 터널이 비정상일 때 Direct로 몰래 우회하지
  않습니다.
- VPN 경유 여부와 관계없이 기존 SSRF 검증을 적용합니다.

## 현재 제한사항

- 현재는 OpenVPN 프로필만 지원합니다. WireGuard는 향후 추가할 수 있습니다.
- 프로필 준비 단계에서는 모든 `remote` 항목을 해석하지만, 현재 Gluetun은 Custom
  프로필의 첫 번째 `remote` 항목만 사용합니다.
- 외부 절대 인증서 및 키 경로는 허용하지 않습니다. 참조 파일은 프로필 디렉터리
  내부에 두어야 합니다.
- Endpoint DNS는 프로필 준비를 실행할 때 한 번만 해석됩니다. TTL cache 또는
  Gluetun 재시작 중 자동 재해석 기능은 아직 없습니다.
- OVPN 파일은 신뢰할 수 있는 관리자가 제공한 입력으로 간주합니다. OpenVPN
  프로필에는 강력한 option이 포함될 수 있으므로 신뢰하는 VPN 제공자에게서만
  내려받아야 합니다.
- 터널 health가 정상이라는 것은 Gluetun이 터널을 정상으로 판정했다는 뜻입니다.
  특정 국가, 특정 공인 IP 또는 모든 DNS leak의 부재를 증명하지는 않습니다.

참고 문서:

- [Gluetun Custom Provider](https://github.com/qdm12/gluetun-wiki/blob/main/setup/providers/custom.md)
- [Gluetun OpenVPN 설정 파일](https://github.com/qdm12/gluetun-wiki/blob/main/setup/openvpn-configuration-file.md)
