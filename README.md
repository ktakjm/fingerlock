# FingerLock

指定したアプリの起動時に、OS標準の認証(指紋・顔・PIN・パターン)を要求するAndroidアプリ。

## 仕組み

- **検知**: ユーザー補助サービス(AccessibilityService)で `TYPE_WINDOW_STATE_CHANGED` を監視し、ロック対象アプリのActivityが前面に来たことを即時検知する。画面内容は読み取らない(`canRetrieveWindowContent="false"`)。
- **表示**: 「他のアプリの上に表示」権限によるBackground Activity Launch免除を使い、不透明なロック画面(`FLAG_SECURE`)を最前面に起動する。
- **認証**: `androidx.biometric` の BiometricPrompt(`BIOMETRIC_STRONG | DEVICE_CREDENTIAL`)。認証UIは完全にOS標準。
- **再ロック**: 猶予時間(即時 / 30秒 / 1分 / 5分)+ 画面OFF時の全再ロック(ON/OFF)。

## 動作要件

- Android 12(API 31)以上。開発ターゲットは Android 16(API 36)。
- サイドロード(adb / Android Studio)前提。Playストア配布は想定していない。

## セットアップ

```sh
./gradlew :app:installDebug
```

初回起動時に以下の2つを許可する(アプリ内から設定画面へ誘導される):

1. **他のアプリの上に表示**(オーバーレイ)
2. **ユーザー補助サービス**(FingerLock アプリロック)

その後、一覧からロックしたいアプリをトグルで選択する。

## 制限(仕様)

このアプリは覗き見への抑止であり、データ保護ではない。以下の経路では突破・迂回できる:

- 本アプリのアンインストール・強制停止・ユーザー補助のOFF(緩和策: 「設定」アプリ自体をロック対象に含める)
- セーフモードでの起動
- 通知・ウィジェット・共有シートなど、起動以外の経路での情報表示
- 最近使ったアプリ画面に残る対象アプリのサムネイル
