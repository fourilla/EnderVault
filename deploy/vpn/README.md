# Optional VPN Egress

EnderVault는 애플리케이션과 함께 비공개 Gluetun HTTP proxy를 실행할 수 있습니다.
이 프로필은 선택 사항이며, 기본 `compose.yaml`은 VPN 파일이나 VPN 권한 없이
계속 실행됩니다.

VPN 컨테이너는 아웃바운드 proxy 인프라만 제공합니다. 각 EnderVault 기능은
설정된 `NetworkRoute`를 계속 사용하므로, 프로필을 활성화하는 것만으로 Remote
Download나 북마크 메타데이터 요청이 자동으로 VPN을 통과하지는 않습니다.

## 비밀정보 준비

Git에서 제외되는 런타임 디렉터리를 만듭니다.

```bash
mkdir -p deploy/secrets/vpn/config
```

OpenVPN 프로필을 다음 위치에 둡니다.

```text
deploy/secrets/vpn/config/custom.ovpn
```

Gluetun은 DNS leak를 피하기 위해 터널 연결 전 DNS 해석을 차단합니다. custom
OpenVPN 프로필의 `remote` endpoint에는 hostname 대신 IP 주소를 사용하세요.
프로필이 참조하는 파일은 다음처럼 `/gluetun/custom` 아래의 컨테이너 절대
경로를 사용해야 합니다.

```text
ca /gluetun/custom/ca.crt
```

인증정보 파일을 만듭니다.

```text
deploy/secrets/vpn/openvpn_user
deploy/secrets/vpn/openvpn_password
```

각 파일에는 대응하는 값만 기록합니다. OpenVPN 프로필이 사용자 이름/비밀번호
인증을 사용하지 않는다면 Compose가 선언된 secret을 마운트할 수 있도록 두
파일을 빈 파일로 만드세요.

경로와 프로필 이름은 `.env`에서 변경할 수 있습니다. 인증정보 자체는 `.env`에
넣지 마세요. `deploy/secrets` 전체는 Git과 Docker build context에서
제외됩니다.

## EnderVault 설정

먼저 저장소 README의 절차대로 기본 설정 파일을 생성합니다. 그다음
`deploy/runtime/state/endervault-nas.properties`에 다음 값을 설정합니다.

```properties
nas.outbound.vpn.enabled=true
nas.outbound.vpn.proxy-host=vpn
nas.outbound.vpn.proxy-port=8888
```

`vpn`은 비공개 Compose service 이름입니다. `8888` 포트와 Gluetun control
API는 호스트에 공개되지 않습니다.

## 실행

기본 애플리케이션과 선택형 VPN 프로필을 함께 실행합니다.

```bash
docker compose -f compose.yaml -f compose.vpn.yaml --profile vpn up -d
```

VPN 컨테이너 로그를 확인합니다.

```bash
docker compose -f compose.yaml -f compose.vpn.yaml logs -f vpn
```

배포를 종료합니다.

```bash
docker compose -f compose.yaml -f compose.vpn.yaml --profile vpn down
```

## 현재 제한사항

- EnderVault는 현재 proxy TCP 포트 도달 가능성만 검사합니다. 이는 OpenVPN
  터널 정상 여부나 예상한 public egress IP 사용을 증명하지 않습니다.
- HTTPS 요청에는 Gluetun HTTP proxy의 `CONNECT` 지원이 필요합니다. 실제
  proxy protocol과 egress IP는 Docker 환경에서 통합 검증해야 합니다.
- 기능별 Direct/VPN 선택은 아직 설정 UI에 노출되지 않았습니다.
- `VPN_REQUIRED` 요청은 VPN 실패 시 direct 경로로 fallback하지 않습니다.
- app과 VPN은 현재 하나의 비공개 Compose bridge를 공유합니다. reverse
  proxy를 추가할 때 frontend와 outbound-proxy network 분리를 검토합니다.

Custom OpenVPN 설정 세부사항은
[Gluetun custom provider 문서](https://github.com/qdm12/gluetun-wiki/blob/main/setup/providers/custom.md)와
[OpenVPN configuration file 문서](https://github.com/qdm12/gluetun-wiki/blob/main/setup/openvpn-configuration-file.md)를
기준으로 합니다.
