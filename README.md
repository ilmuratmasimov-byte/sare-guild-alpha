# ���줨� SARE - Android alpha

<��� ������� �⠭������ �ਪ��祭���.>

���⮩ Android alpha-���⨯ ��� ���஢���� �।� ��㧥�. �ਫ������ ����ᠭ� �� Java � �����뢠�� HTML/JS-����䥩� �१ WebView. ��������� � �室 ࠡ���� �१ Firebase Authentication; ��⮢� ������ � ᮮ�饭�� ���� �࠭���� ⮫쪮 �� ���ன�⢥ � `localStorage`.

## ��ࠬ����

- package: `kz.sare.guild`
- minSdk: 26
- compileSdk / targetSdk: 35
- Java: 17
- Gradle: 8.9
- Android Gradle Plugin: 8.7.3
- ��砫쭠� �����: `1` / `0.1.0`

## ���ઠ

GitHub Actions ᮡ�ࠥ� �����ᠭ�� release APK �� push � `main`, ��筮� ����᪥ � ᮧ����� ⥣� ���� `v0.1.0`. �� ����᪥ �� ⥣� workflow ⠪�� �㡫���� GitHub Release � 䠩����:

- `SARE-Guild.apk`
- `version.json`

��� ������ �㦭� repository secrets:

- `SARE_KEYSTORE_BASE64`
- `SARE_KEYSTORE_PASSWORD`
- `SARE_KEY_ALIAS`
- `SARE_KEY_PASSWORD`
- `SARE_FIREBASE_API_KEY`

Keystore � ��஫� ����� ��������� � Git. ����� ������ ⮫쪮 � `version.properties`; ��� ������� ���������� `VERSION_CODE` ������ 㢥��稢�����.

## ���ਧ���

Firebase-�஥��: `sare-guild-alpha-kz`. ������ �室 �� email � ��஫�. �ਫ������ �����ন���� ॣ������, �室, ����⠭������� ��஫�, ��࠭���� ��ᨨ � ��室. ������᪨� Firebase API-���� ��।����� � ᡮ�� �१ secret `SARE_FIREBASE_API_KEY` � �� �࠭���� � ��室�����.

## ���������� ��� Google Play

������ **��䨫�  ����ன��  �஢���� ����������** ����砥� �㡫��� 䠩�:

`https://github.com/ilmuratmasimov-byte/sare-guild-alpha/releases/latest/download/version.json`

�᫨ `versionCode` ��� ��⠭���������, �ਫ������ �।������ ᪠��� APK. ��᫥ ����㧪� Android ���뢠�� �⠭���⭮� ��⥬��� ���⢥ত���� ��⠭����. ������ ��⠭���� �� �ᯮ������.

�⮡� ���������� ��⠭������� ������ ⥪�饩 ���ᨨ, package name � signing key ������ ��⠢����� ��������묨, � `VERSION_CODE` - 㢥��稢�����.

