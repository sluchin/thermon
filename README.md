# thermon

AWS に保存された温度・湿度のデータを読み取って、表示する Android アプリです (Kotlin)。

姉妹プロジェクト [esp32-thermo-system](https://github.com/sluchin/esp32-thermo-system) (ESP32 の温度センサのノードと、ゲートウェイ) が AWS IoT Core 経由で、DynamoDB に保存したデータを Cognito の ID プールの未認証ロールで、読み取ります (ログインはありません)。

```
ESP32 のノード ──BLE──> ゲートウェイ ──MQTT──> AWS IoT Core ──ルール──> DynamoDB
                                                                          ▲ Query (読み取りだけ)
Android アプリ (thermon) ──> Cognito ID プール (未認証) ──一時的な認証情報──┘
```

**Android Studio は使いません。** ビルド、テスト、整形、静的解析、ドキュメントの生成は全てコマンドラインで、Docker の中で実行します。

> **状態**: 雛形と、AWS のクライアント (Cognito の未認証の認証情報と、DynamoDB の Query) を実装しました。グラフの表示 (温度と湿度。範囲は 1 h、6 h、24 h) も、実装しています。単体テストは AWS SDK のクライアントを偽物にして、行います ([SETUP.md](SETUP.md))。

## 画面

<a href="assets/thermon-chart.png"><img src="assets/thermon-chart.png" width="320" alt="thermon の画面 (室内と室外の温度と湿度のグラフ)"></a>

室内 (オレンジ) と室外 (青) の、温度と湿度のグラフです (MAC アドレスは画像の中で、隠してあります)。

## 機能

- 機器 (MAC アドレス) ごとの、最新の温度・湿度と、受信した時刻を 1 行で表示する (電池残量は `Reading` に入っているが、まだ表示しない)。
- 温度と湿度の 2 つの折れ線グラフに機器 (室内、室外など) を重ねて表示する。機器はグラフの上のボタンで、表示を切り替える (表示名は `thermon.nodes` の `MAC アドレス=室外` の形で付ける)。範囲は画面の上のボタンで、1 時間、6 時間、24 時間、7 日、30 日、1 年から選ぶ。**開始と終了の日付**は、その下の 2 つのボタン (カレンダー) でも選べる (開始の日の 0 時から、終了の日の終わりまで。終了が今日なら、今までの分)。起動したときは、日付は未設定 (`-`)。片方だけを選んだときは、開始がなければ終了の日だけ、終了がなければ今日まで。`Clear` で日付を消すと、6 時間に戻る。測定値を区切りごとに平均する。測定値のない時間は線を切る (保存の間隔の 1 時間を考えて、2 時間までの穴はつなぐ)。**グラフを長押し**すると、縦線と、その時刻と値(機器ごと)が出る。押したまま指を動かすと縦線が付いてきて、離すと消える。
- **室外の通知**: 表示名が「室外」の機器の温度が 0 ℃ を下回る、または、湿度が 80 % を超えると、通知する (アプリを閉じていても、15 分ごとに確認する。状態が続く間は、繰り返さない。1 時間以上測定値が届かないときは、判定しない)。初めて開いたときに、通知の許可を求める (Android 13 以降)。
- **測定値を端末に保存する (1 年のグラフ)**: AWS から取り出した測定値を、端末の SQLite に保存する。グラフは、保存した測定値から描くので、1 年のような長い期間も表示できる。アプリを開いたときと、通知の確認 (15 分ごと) のときに、新しい測定値を取り出して保存する。AWS に届かないとき (オフライン) は、保存した測定値だけで表示して、メッセージを付ける。保存したデータは、アプリのデータを消す (設定の「ストレージを消去」、アンインストール) と、消える。
  - **保存するのは、変化があったときと、1 時間に 1 点**: 前に保存した値から温度か湿度が変わった測定値を保存する。値が変わらなくても、1 時間に 1 点は保存する (機器が止まっていた間と、変わらなかった間を、区別するため)。
  - **30 日の制限**: AWS (DynamoDB) は、測定値を **30 日までしか残さない**。端末に保存できるのは、アプリが AWS から取り出せた分だけで、**30 日より古い分は、あとから取り出せない**。アプリを 30 日以上開かず、通知の確認も動かなかった間 (電源オフ、通信なし、通知の停止など) の測定値は、保存されず、グラフに穴があく。最初に開いたときは、そのとき AWS に残っている 30 日分を取り込む。
- AWS への接続は Cognito の未認証の ID の、一時的な認証情報だけを使う (アクセスキーは使わない)。権限はそのテーブルの `dynamodb:Query` だけ。
- DynamoDB から、機器と時刻の範囲を指定して、測定値を古い順に取り出せる (`core` の `DynamoDbReadings.range()`。グラフの元になる)。

## プロジェクト構成

```
core/    純粋な Kotlin (Android に依存しない). AWS のクライアントと, 測定値の処理. 単体テストで, 行と分岐のカバレッジ 100%
app/     Android アプリ. 画面と, 設定の読み込みだけの, 薄い層
config/  detekt (静的解析) の設定
gradle/  版カタログ (libs.versions.toml) と, Gradle ラッパー
scripts/ エミュレータの起動, クラウドのセッションで Docker を使う補助, Maven のミラーの設定
.github/ GitHub Actions (CI と, ドキュメントの公開)
```

## 必要なもの

- Docker と Docker Compose V2 (`docker compose`)。JDK、Gradle、Android SDK は Docker のイメージに入っているので、ホストには入れません。
- 実機に入れるときだけ、ホストに adb。

## ビルド方法

### 1. 準備 (最初の 1 回)

コンテナをホストのユーザーで実行するため、`UID` と `GID` をシェルの設定ファイルで、エクスポートします (生成物が root 所有になりません)。bash の場合 (`~/.bashrc`):

```bash
export UID
export GID=$(id -g)
```

zsh の場合 (`~/.zshrc`) は `export UID` と `export GID` です。新しいシェルを開いて、確認します。

```bash
docker compose run --rm dev id
```

### 2. イメージのビルド

JDK 17、Gradle 9.8.1、Android SDK (API 37) を含むイメージを作ります (約 2 分、約 2.2 GB。ネットワークが必要です)。

```bash
docker compose build
```

### 3. アプリのビルド

```bash
docker compose run --rm build
```

デバッグ用の APK が `app/build/outputs/apk/debug/thermon-<版>-debug.apk` にできます。初回は依存関係のダウンロードで、数分かかります (Gradle のキャッシュは Docker のボリュームに残るので、2 回目からは速くなります)。

## 設定 (AWS への接続)

接続先はリポジトリに入れません。`local.properties` (コミットしない) をリポジトリの直下に作って、書きます。

```properties
thermon.region=ap-northeast-1
thermon.identityPoolId=ap-northeast-1:xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx
thermon.table=thermo-readings
# 機器の MAC アドレス (コンマ区切り). =のあとに表示名を付けられる (UTF-8)
thermon.nodes=AA:BB:CC:DD:EE:01=室外,AA:BB:CC:DD:EE:02=室内
```

`thermon.identityPoolId` と `thermon.nodes` が空のままだと、アプリは「AWS is not configured」と表示します。値を変えたら、ビルドし直します。AWS 側 (テーブル、IoT のルール、ID プール、未認証ロール) の準備は [AWS_DYNAMODB.md](AWS_DYNAMODB.md) を参照してください。詳しくは [SETUP.md](SETUP.md) の「AWS への接続」です。

## 実機へのインストール

adb はホストで実行します (端末の設定を含む手順は [SETUP.md](SETUP.md) の「実機へのインストール」)。

```bash
adb install -r app/build/outputs/apk/debug/thermon-*-debug.apk
```

インストールしたら、端末のアプリ一覧から **`thermon`** を開きます。AWS の準備のあとに、アプリを動かして確認する手順と、うまくいかないときの確認先は [SETUP.md](SETUP.md) の「アプリを動かして確認する」です。

## エミュレータ

Android のエミュレータを Docker の中で動かして、PC の上で、アプリを確認できます (KVM を使います。画面はブラウザで見ます)。

```bash
export KVM_GID=$(getent group kvm | cut -d: -f3)   # 最初の 1 回 (シェルの設定ファイルに書く)
docker compose build emulator                        # 約 9 分, 約 10.7 GB (最初の 1 回)
docker compose up -d emulator                        # 起動
docker compose exec emulator scripts/emulator-wait.sh
docker compose exec emulator adb install -r app/build/outputs/apk/debug/thermon-*-debug.apk
docker compose exec emulator adb shell am start -n jp.thermon.app/.MainActivity
```

画面は <http://localhost:6080/vnc.html> で見ます。止めるときは `docker compose down` です。詳しくは [SETUP.md](SETUP.md) の「エミュレータ (Docker)」です。

## 開発

使えるコマンドは次のとおりです (どれも `docker compose run --rm <サービス>`)。

| サービス | 内容 |
|:---|:---|
| `build` | デバッグ用の APK をビルドする。Kotlin の警告が出たら失敗する |
| `test` | 単体テストを実行する |
| `coverage` | `core` の行と分岐のカバレッジが 100% でなければ失敗する |
| `format` | 整形が必要なら失敗する (確認だけ。ktlint) |
| `format-fix` | 整形して、ファイルを書き換える |
| `analyze` | 静的解析 (detekt と Android Lint)。指摘があれば失敗する |
| `docs` | ドキュメント (Dokka の HTML) を生成する (`core/build/dokka/html/index.html`)。KDoc のない公開の宣言があれば失敗する |
| `clean` | 生成物 (`build/` など) を消す (Gradle のキャッシュは消さない) |
| `emulator` | Android エミュレータを KVM で動かす (`docker compose up -d emulator`) |
| `dev` | 開発シェル (中で `./gradlew` を直接実行できる) |

コードを修正したら、`format-fix`、`format`、`analyze`、`test`、`coverage`、`build`、`docs` の順に全て成功することを確認します。コーディング規約、テストの方針、コミットの書き方は [.claude/CLAUDE.md](.claude/CLAUDE.md) にあります。

## CI

Push 時に GitHub Actions が開発用の Docker イメージで、`build` `test` `coverage` `format` `analyze` `docs` を実行します。ログはアーティファクトに保存します (APK は置きません)。main への push では、ドキュメント (Dokka) を GitHub Pages に公開します。詳しくは [SETUP.md](SETUP.md) の「CI (GitHub Actions)」です。

## ドキュメント

- [AWS_CLI.md](AWS_CLI.md): AWS CLI の準備 (インストール、`aws login`、IAM ユーザーとアクセスキー、CloudShell)。
- [AWS_DYNAMODB.md](AWS_DYNAMODB.md): AWS 側の準備 (DynamoDB のテーブル、IoT のルール、Cognito の ID プール、未認証ロール)。
- [SETUP.md](SETUP.md): 開発環境の構築と、検証の記録 (ツールの版、落とし穴、AWS への接続の詳細)。
- [.claude/CLAUDE.md](.claude/CLAUDE.md): コマンド、コーディング規約、テストの方針、コミットガイドライン。

## ライセンス

GPL-3.0-or-later ([LICENSE](LICENSE))。
