# fingerlock プロジェクト規約

指定アプリの起動時にOS標準認証(BiometricPrompt)を要求するアプリロッカー。
仕組みの概要と突破経路(仕様上の割り切り)は README.md、今後の機能仕様は GitHub issues #1〜#4 を参照。

## ビルド

- Gradle本体は未インストール。wrapper(8.14.3)を直接配置してあるので必ず `./gradlew` を使う
- JDKは22を明示指定する(シェルデフォルトが別バージョンのことがある):

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 22) ./gradlew :app:assembleDebug
JAVA_HOME=$(/usr/libexec/java_home -v 22) ./gradlew :app:installDebug
```

- minSdk 31 / targetSdk 36。実機はAndroid 16のみ。レガシー分岐(`Build.VERSION` チェック)は原則書かない

## 作業フロー

- 実装 → `./gradlew :app:assembleDebug` で確認 → ユーザーが実機検証 → **ユーザーの指示があってからコミット・プッシュ**(勝手にコミットしない)
- 実機は手元のAndroid 16の1台のみ。インストールは `./gradlew :app:installDebug`
- 機能仕様・バックログはGitHub issuesで管理(Project: users/ktakjm/projects/2)。実装完了・実機検証OKになったissueはユーザー確認のうえクローズ

## 設計上の不変条件(変更・違反する前に要相談)

- **`AppLockAccessibilityService` は自パッケージと `com.android.systemui` を無視する**。これを外すとLockActivity表示→検知→再表示の無限ループになる
- **ロック画面の起動は `SYSTEM_ALERT_WINDOW` 許可によるBAL免除に依存している**。オーバーレイ許可の誘導をセットアップから外すとサービスからのActivity起動がOSにブロックされる
- **窓イベントは「実在するActivityか」を `getActivityInfo` で確認してから処理する**(`isActivity`)。外すとIMEやシステムダイアログを前面アプリとして誤検知し、猶予セッションが壊れる
- **`LockActivity` にはセルフロック(issue #2)を適用しない**。二重認証・ループになる
- **解除セッションの状態はプロセス内メモリ(`LockStateManager`)のみ**。永続化するとサービス再起動時に古い解除状態が復活してしまうので、DataStoreに保存するのは設定値だけ
- **失敗アラートの発火経路は `FailureAlertDispatcher.fire()` に一本化する**。トリガー(閾値到達・ロックアウト・issue #7のキャンセル検知)とアクション(通知・撮影・issue #4のメール)を分離しておくため、`LockActivity` / `MainActivity` はイベントを渡すだけにしてアクションを直接呼ばない
- **インカメラ撮影(issue #3)はActivityのライフサイクルにバインドしない**。`IntruderCamera` 内の専用 `LifecycleOwner` に繋ぎ、`onStop` と発火完了時に明示的に `release()` する。バックグラウンドではカメラを保持できず、撮影中の離脱で切断されるため、ロックアウト経路は撮影完了(`onComplete`)を待ってから画面を閉じる
- 画面OFF→ONで同一アプリが前面のままの場合、windowイベントは発火しない。再ロックは `ACTION_USER_PRESENT` 受信時の `pendingLockTarget()` チェックで実現している
- ユーザー補助の設定は `canRetrieveWindowContent="false"` を維持する(画面内容を読まないことが権限説明の根拠)

## 既知の制限(仕様として受け入れ済み)

- BiometricPromptのPIN/パターンフォールバック内の失敗は `onAuthenticationFailed` が呼ばれないため検知できない(issue #1の失敗カウントは生体認証のみ対象)
- 本アプリは覗き見抑止であってデータ保護ではない(突破経路はREADME参照)

## 方針

- 個人利用・サイドロード前提。Playストアの審査要件(ユーザー補助の用途制限、QUERY_ALL_PACKAGES)は考慮しない
- UI・認証は極力OS標準を流用する(自前パスコード画面などは作らない)
- コミットは日本語でもよいが、これまでは英語。GitHubリポジトリは private(ktakjm/fingerlock)
