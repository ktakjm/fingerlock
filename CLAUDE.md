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
- **`LockStateManager.onForeground()` を「同一パッケージなら即 false」にしてはいけない**。上記のとおりロック画面表示中は `currentForeground` が対象アプリのまま固定されるため、対象アプリが自タスクを前面に戻したときに再ロックできなくなる(issue #8)。判定はあくまで解除セッション(`sessions[pkg]`)の有無で行い、パッケージの一致は離脱時刻の記録にだけ使う
- **ロック画面の起動は `SYSTEM_ALERT_WINDOW` 許可によるBAL免除に依存している**。オーバーレイ許可の誘導をセットアップから外すとサービスからのActivity起動がOSにブロックされる
- **窓イベントは「実在するActivityか」を `getActivityInfo` で確認してから処理する**(`isActivity`)。外すとIMEやシステムダイアログを前面アプリとして誤検知し、猶予セッションが壊れる
- **`LockActivity.onNewIntent` は同一ターゲットに対して冪等に保つ**。上記の再ロックで同じアプリの検知が繰り返されるため、カウンタをリセットしたりプロンプトを出し直したりするとセッションが壊れ、プロンプトがちらつく
- **`LockActivity` にはセルフロック(issue #2)を適用しない**。二重認証・ループになる
- **解除セッションの状態はプロセス内メモリ(`LockStateManager`)のみ**。永続化するとサービス再起動時に古い解除状態が復活してしまうので、DataStoreに保存するのは設定値だけ
- **失敗アラートの発火経路は `FailureAlertDispatcher.fire()` に一本化する**。トリガー(閾値到達・ロックアウト・キャンセル検知)とアクション(通知・撮影・issue #4のメール)を分離しておくため、`LockActivity` / `MainActivity` はイベントを渡すだけにしてアクションを直接呼ばない。種別ごとの実行アクションは `FailureAlertDispatcher.actionsFor()` の1箇所で決める
- **キャンセル検知(issue #7)の対象は `ERROR_USER_CANCELED` のみ**。`ERROR_CANCELED`(コード5)は画面OFFやホームキーなどシステム都合でも飛ぶので数えてはいけない
- **`ERROR_USER_CANCELED` も即カウントしてはいけない**。SystemUIは表示中に別タスクが前面に来るとプロンプトを強制クローズし(タスク退去)、それも本物のキャンセルと同じ `ERROR_USER_CANCELED` で届く(issue #10)。判定は `DismissJudge` に一本化する: (1) 表示から400ms未満の強制クローズは人間ではないので棄却、(2) 受信後500msを前面(resumed)のまま生き残れば本物、(3) 前面を失った場合(**退去では `onPause` がエラーより先に届くこともある**)は離脱先で判定し、ホーム/ランチャーならユーザーの離脱として数え、他アプリのActivityなら退去として棄却(前面の観測は `LockStateManager.foregroundPackage()`)。「閉じる」・戻るの明示離脱は `flush()` を離脱処理より先に呼んで即確定する。ロック画面・セルフロックの両方に適用する
- **キャンセルの間引きはセッション単位ではなくパッケージ単位のクールダウンで行う**。ロック画面は閉じても前面に残る(閉じる→再試行→閉じるが同一セッション)一方、セルフロックはキャンセルで即 `finish()` して毎回新しいセッションになるため、セッション単位では片方にしか効かない
- **`FailureEvent` へのフィールド追加は必ずデフォルト値付きにする**。kotlinx-serializationのデフォルト値で既存の履歴JSONをそのまま読めるようにし、マイグレーションを不要に保つ
- **インカメラ撮影(issue #3)はActivityのライフサイクルにバインドしない**。`IntruderCamera` 内の専用 `LifecycleOwner` に繋ぎ、`onStop`・認証成功・発火完了時に明示的に `release()` する
- **画面を閉じる処理は `FailureAlertDispatcher.awaitInFlight()` を通す**。バックグラウンドに落ちるとカメラが切断されて撮影が失われるため、`goHome()` や「閉じる」は撮影完了(最大1.5秒)を待つ。あわせて、認証を求めている間はカメラを予熱しておく(キャンセルは閾値と違って予告がないため)
- 画面OFF→ONで同一アプリが前面のままの場合、windowイベントは発火しない。再ロックは `ACTION_USER_PRESENT` 受信時の `pendingLockTarget()` チェックで実現している
- ユーザー補助の設定は `canRetrieveWindowContent="false"` を維持する(画面内容を読まないことが権限説明の根拠)

## 既知の制限(仕様として受け入れ済み)

- BiometricPromptのPIN/パターンフォールバック内の失敗は `onAuthenticationFailed` が呼ばれないため検知できない(issue #1の失敗カウントは生体認証のみ対象)
- 本アプリは覗き見抑止であってデータ保護ではない(突破経路はREADME参照)

## 方針

- 個人利用・サイドロード前提。Playストアの審査要件(ユーザー補助の用途制限、QUERY_ALL_PACKAGES)は考慮しない
- UI・認証は極力OS標準を流用する(自前パスコード画面などは作らない)
- コミットは日本語でもよいが、これまでは英語。GitHubリポジトリは private(ktakjm/fingerlock)
