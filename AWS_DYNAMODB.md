# AWS に届いたデータを DynamoDB に保存して、アプリから読む

AWS IoT Core に届いた温度・湿度・電池残量を、DynamoDB に 1 件ずつ保存して、Android アプリ (thermon) から読む手順です。アプリは、Cognito の「未認証ロール」で、読み取りだけを許可されます (ログインは、ありません)。

> **注意**: この手順は、AWS のドキュメントに基づいて書いたものです。このリポジトリでは、AWS CLI の実行結果を、確認していません。コマンドの結果が、ここに書いたものと違うときは、文書を直してください。AWS の画面、料金、サービスの提供状況は、変わることがあるので、最新の情報を、確認してください。

AWS CLI を、まだ使えるようにしていないときは、先に [AWS_CLI.md](AWS_CLI.md) の手順を行います (CLI のインストール、認証情報の設定、アカウント ID の確認)。

## 1. 構成

```
ゲートウェイ ──MQTT──> AWS IoT Core ──ルール──> DynamoDB (テーブル thermo-readings)
                          thermo/#                       ▲
                                                         │ Query (読み取りだけ)
thermon ──> Cognito ID プール (未認証) ──一時的な認証情報──┘
```

- **ルール**: 届いた MQTT のメッセージを、SQL で絞り込んで、別のサービスに渡す、IoT Core の機能です。DynamoDBv2 アクションは、メッセージの項目を、そのまま、テーブルの属性として書きます。
- **Cognito ID プール**: アプリに、AWS の一時的な認証情報を渡す仕組みです。「未認証ロール」を有効にすると、ログインなしで、決めた権限だけの認証情報を受け取れます。
- ルールの設定だけで保存できます。コードを書くのは、アプリだけです。

### 1.1 届くメッセージ

ゲートウェイは、センサの値を、トピック `thermo/...` (SwitchBot と Thermo ノードの両方) に、JSON で送ります。ルールは、この項目を使います。

| 項目 | 内容 |
|:---|:---|
| `node` | 機器の MAC アドレス (例: `ED:2E:C4:46:3B:11`) |
| `type` | `switchbot` または `thermo` |
| `temperature_c` | 温度 [℃] |
| `humidity` | 湿度 [%] (測れないセンサでは、ない) |
| `battery` | 電池残量 [%] (SwitchBot だけ。わからないときは、ない) |
| `uptime_ms` | ゲートウェイの稼働時間 [ms] |
| `timestamp` | ゲートウェイの時計の時刻 (時計が合っていないと、ない) |

### 1.2 DynamoDB と Timestream

| 項目 | DynamoDB | Timestream |
|:---|:---|:---|
| 時系列の保存 | キーの設計で対応する (機器と時刻) | 時系列専用 |
| 設定の手間 | 少ない | 多い |
| 提供 | 常に利用できる | Timestream for LiveAnalytics は、2025 年 6 月から、新しい利用者を受け付けていない (私の知る限り。最新の状況は、AWS で確認してください)。InfluxDB 版は、別のサービス |
| 向くこと | 機器と期間を指定して取り出す。個人の規模 | 複雑な集計、SQL での分析 |

このドキュメントは、**DynamoDB** で書きます。個人の規模 (1 台、10 秒に 1 件) なら、十分です。

### 1.3 制限

- **取り出し方**: 機器 (`node`) を指定して、時刻の範囲で取り出します。「全ての機器の、ある時刻のデータ」は、1 回では取り出せません (機器ごとに、取り出します)。
- **集計**: 平均や最大値は、アプリ側で計算します (DynamoDB は、集計しません)。
- **件数と料金**: 10 秒に 1 件だと、1 台で 1 日に 8,640 件、1 か月に約 26 万件です。書き込みは、オンデマンドの課金です。保存が増え続けないように、古い項目を自動で消します (TTL。2.1)。料金は、リージョンごとの料金表で確認してください。

## 2. テーブルを作る

### 2.1 項目の設計

| 属性 | 型 | 内容 |
|:---|:---|:---|
| `node` | 文字列 | パーティションキー。機器の MAC アドレス (例: `ED:2E:C4:46:3B:11`)。メッセージの `node` |
| `ts` | 数値 | ソートキー。IoT Core が受信した時刻 (UNIX 時刻 [s])。ルールが付ける |
| `type` | 文字列 | `switchbot` または `thermo` |
| `temperature_c` | 数値 | 温度 [℃] |
| `humidity` | 数値 | 湿度 [%] (測れないセンサでは、ない) |
| `battery` | 数値 | 電池残量 [%] (SwitchBot だけ。わからないときは、ない) |
| `expires_at` | 数値 | この時刻 (UNIX 時刻 [s]) を過ぎたら、自動で消える (TTL。受信から 30 日) |

- ソートキーに、メッセージの `timestamp` (ゲートウェイの時計) を使わず、ルールの `timestamp()` (IoT Core の時計) を使います。ゲートウェイの時計が合っていないと、`timestamp` が入らないことがあるためです。
- `ts` は、秒単位です。同じ機器から、同じ秒に 2 件届くと、上書きされます。ゲートウェイは、1 台あたり 10 秒に 1 回なので、通常は起きません。

### 2.2 作成する

AWS CLI の例です (リージョンは、`ap-northeast-1`。アカウント ID は、自分のものに置き換えます)。

```bash
aws dynamodb create-table --region ap-northeast-1 \
  --table-name thermo-readings \
  --attribute-definitions AttributeName=node,AttributeType=S AttributeName=ts,AttributeType=N \
  --key-schema AttributeName=node,KeyType=HASH AttributeName=ts,KeyType=RANGE \
  --billing-mode PAY_PER_REQUEST

# 古い項目を自動で消す (属性 expires_at)
aws dynamodb update-time-to-live --region ap-northeast-1 \
  --table-name thermo-readings \
  --time-to-live-specification Enabled=true,AttributeName=expires_at
```

出力の `TableArn` (`arn:aws:dynamodb:ap-northeast-1:<ACCOUNT_ID>:table/thermo-readings`) を、控えます。

コンソールで作る場合は、「DynamoDB → テーブル → テーブルの作成」で、テーブル名 `thermo-readings`、パーティションキー `node` (文字列)、ソートキー `ts` (数値)、設定は「オンデマンド」にします。作成後に、「追加の設定」の「有効期限 (TTL)」を開いて、属性名に `expires_at` を指定します。

## 3. IoT Core が DynamoDB に書き込むための権限 (IAM ロール)

IoT Core が、テーブルに書き込むためのロールを作ります。ロールの種類は、「カスタム信頼ポリシー」にします (「AWS のサービス」の「IoT」を選ぶと、余計な管理ポリシーが付きます)。

### 3.0 ACCOUNT_ID を調べる

以降のコマンドと JSON にある `<ACCOUNT_ID>` は、自分の AWS アカウントの **12 桁の数字** (ハイフンなし) です。先に調べて、控えておきます。

```bash
aws sts get-caller-identity
```

出力の `"Account": "123456789012"` が、`<ACCOUNT_ID>` です (`Arn` の `arn:aws:iam::123456789012:...` にも、同じ番号が入っています)。コンソールでは、右上の自分のアカウント名をクリックすると、「アカウント ID」が表示されます。出力が出ないときは、[AWS_CLI.md](AWS_CLI.md) の設定が終わっていません。

`<ACCOUNT_ID>` を使う場所は、この章の `iot-put.json` (`Resource`) と、4 章のルールの `roleArn`、6 章の `unauth-read.json` (`Resource`) と `set-identity-pool-roles` の `--roles` です。どれも同じ番号に置き換えます。次のように、シェルの変数に入れて、ファイルを作ったあとで、まとめて置き換えることもできます (番号は、自分のものに直します)。

```bash
ACCOUNT_ID=<12 桁のアカウント ID>
sed -i "s/<ACCOUNT_ID>/${ACCOUNT_ID}/g" iot-put.json
```

アカウント ID は、パスワードではありませんが、公開する必要もありません。リポジトリには、書かないでください。

### 3.1 ロールを作る

```bash
cat > iot-trust.json <<'JSON'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": { "Service": "iot.amazonaws.com" },
      "Action": "sts:AssumeRole"
    }
  ]
}
JSON

cat > iot-put.json <<'JSON'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "dynamodb:PutItem",
      "Resource": "arn:aws:dynamodb:ap-northeast-1:<ACCOUNT_ID>:table/thermo-readings"
    }
  ]
}
JSON

aws iam create-role --role-name thermo-iot-dynamodb \
  --assume-role-policy-document file://iot-trust.json
aws iam put-role-policy --role-name thermo-iot-dynamodb \
  --policy-name put-item --policy-document file://iot-put.json
```

出力の `Role.Arn` (`arn:aws:iam::<ACCOUNT_ID>:role/thermo-iot-dynamodb`) を、次の章で使います。

## 4. ルールを作る

### 4.1 SQL の意味

この SQL は、単独では実行しません。4.2 の `rule-dynamodb.json` の `"sql"` に、1 行で入れてあります (4.2 のコマンドで、ルールと一緒に登録されます)。ここでは、その意味だけを説明します。

```sql
SELECT *, floor(timestamp() / 1000) AS ts, floor(timestamp() / 1000) + 2592000 AS expires_at
FROM 'thermo/#'
WHERE isUndefined(temperature_c) = false
```

- `FROM 'thermo/#'` は、`thermo/` で始まる全てのトピックです (SwitchBot と、Thermo ノードの両方)。
- `WHERE` で、`temperature_c` のないメッセージを、除きます。
- `timestamp()` は、IoT Core が受信した時刻 [ミリ秒] です。`floor(... / 1000)` で秒にします。`2592000` は、30 日の秒数です。
- `SELECT *` で、メッセージの全ての項目 (`node`、`type`、`temperature_c`、`humidity`、`battery`、`uptime_ms`、`timestamp`) を、そのまま渡します。

### 4.2 ルールを作る

```bash
cat > rule-dynamodb.json <<'JSON'
{
  "sql": "SELECT *, floor(timestamp() / 1000) AS ts, floor(timestamp() / 1000) + 2592000 AS expires_at FROM 'thermo/#' WHERE isUndefined(temperature_c) = false",
  "awsIotSqlVersion": "2016-03-23",
  "ruleDisabled": false,
  "actions": [
    {
      "dynamoDBv2": {
        "roleArn": "arn:aws:iam::<ACCOUNT_ID>:role/thermo-iot-dynamodb",
        "putItem": { "tableName": "thermo-readings" }
      }
    }
  ]
}
JSON

# <ACCOUNT_ID> を置き換える (3.0 で設定した変数。別のシェルなら、もう一度設定する)
ACCOUNT_ID=<12 桁のアカウント ID>
sed -i "s/<ACCOUNT_ID>/${ACCOUNT_ID}/g" rule-dynamodb.json

aws iot create-topic-rule --region ap-northeast-1 \
  --rule-name thermo_to_dynamodb --topic-rule-payload file://rule-dynamodb.json

# 登録されたルールを確認する
aws iot get-topic-rule --region ap-northeast-1 --rule-name thermo_to_dynamodb
```

ルール名は、英数字とアンダースコアだけです。

コンソールで作る場合は、「IoT Core → メッセージのルーティング → ルール → ルールを作成」で、SQL に 4.1 を貼って、アクションは「DynamoDB (DynamoDBv2)」を選びます (「DynamoDB」ではなく、メッセージの項目を属性に分ける「DynamoDBv2」)。テーブルは `thermo-readings`、IAM ロールは `thermo-iot-dynamodb` です。

## 5. データが届いているか確認する

ゲートウェイを動かして、少し待ってから、確認します。

```bash
# 件数と、最新の数件 (Scan は、少量のテーブルで、確認に使うだけにしてください)
aws dynamodb scan --region ap-northeast-1 --table-name thermo-readings --max-items 5

# 機器を指定して、最新の 3 件を取り出す
aws dynamodb query --region ap-northeast-1 --table-name thermo-readings \
  --key-condition-expression "#n = :n" \
  --expression-attribute-names '{"#n":"node"}' \
  --expression-attribute-values '{":n":{"S":"<機器の MAC アドレス>"}}' \
  --no-scan-index-forward --limit 3
```

何も入らないときは、ルールの「エラーアクション」と、CloudWatch Logs (IoT のログを有効にしたとき) を見ます。よくある原因は、ロールの権限不足 (`dynamodb:PutItem`)、テーブル名やリージョンの違い、キーの `node` か `ts` が、SQL の結果にない場合です。

## 6. アプリ用の認証 (Cognito ID プール、未認証ロール)

### 6.1 ID プールを作る

```bash
aws cognito-identity create-identity-pool --region ap-northeast-1 \
  --identity-pool-name thermo_client \
  --allow-unauthenticated-identities
```

出力の `IdentityPoolId` (`ap-northeast-1:xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`) を、控えます。アプリに書く値です。

### 6.2 未認証ロール (読み取りだけ)

```bash
# 6.1 で控えた IdentityPoolId と、3.0 のアカウント ID (別のシェルなら、もう一度設定する)
ACCOUNT_ID=<12 桁のアカウント ID>
IDENTITY_POOL_ID=<6.1 の IdentityPoolId>

cat > unauth-trust.json <<'JSON'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": { "Federated": "cognito-identity.amazonaws.com" },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": { "cognito-identity.amazonaws.com:aud": "<IDENTITY_POOL_ID>" },
        "ForAnyValue:StringLike": { "cognito-identity.amazonaws.com:amr": "unauthenticated" }
      }
    }
  ]
}
JSON

cat > unauth-read.json <<'JSON'
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": "dynamodb:Query",
      "Resource": "arn:aws:dynamodb:ap-northeast-1:<ACCOUNT_ID>:table/thermo-readings"
    }
  ]
}
JSON

# <IDENTITY_POOL_ID> と <ACCOUNT_ID> を置き換える
sed -i "s/<IDENTITY_POOL_ID>/${IDENTITY_POOL_ID}/g" unauth-trust.json
sed -i "s/<ACCOUNT_ID>/${ACCOUNT_ID}/g" unauth-read.json

aws iam create-role --role-name thermo-client-unauth \
  --assume-role-policy-document file://unauth-trust.json
aws iam put-role-policy --role-name thermo-client-unauth \
  --policy-name read-thermo --policy-document file://unauth-read.json

# ID プールに、未認証ロールとして登録する
aws cognito-identity set-identity-pool-roles --region ap-northeast-1 \
  --identity-pool-id "${IDENTITY_POOL_ID}" \
  --roles unauthenticated=arn:aws:iam::${ACCOUNT_ID}:role/thermo-client-unauth

# 登録されたロールを確認する
aws cognito-identity get-identity-pool-roles --region ap-northeast-1 \
  --identity-pool-id "${IDENTITY_POOL_ID}"
```

`<IDENTITY_POOL_ID>` は 6.1 の値、`<ACCOUNT_ID>` は、自分のアカウント ID です。`sed` で、`unauth-trust.json` と `unauth-read.json` の中の文字列を置き換えます。

### 6.3 安全のために

未認証ロールは、**アプリの中の ID プール ID を知っている人なら、誰でも**、この権限を使えます (ID は、アプリのファイルから取り出せます)。次の点を守ってください。

- 権限は、`dynamodb:Query` だけを、このテーブルだけに付けます (上のポリシーのとおり)。書き込み、削除、ほかのサービスは、付けません。
- アクセスキーを、アプリに埋め込みません。必ず、Cognito の一時的な認証情報を使います。
- アプリを、ほかの人に配るときは、Cognito のユーザープールによるログインに替えます (今は、自分だけなので、未認証ロールで進めます)。

## 7. thermon から読む

thermon の `core` が、この手順で作った ID プールとテーブルを使います。接続先は、リポジトリに入れず、`local.properties` に書きます ([README.md](README.md) の「設定」)。

```properties
thermon.region=ap-northeast-1
thermon.identityPoolId=<6.1 の IdentityPoolId>
thermon.table=thermo-readings
thermon.nodes=<機器の MAC アドレス (コンマ区切り)>
```

- `CognitoUnauthenticatedCredentials` が、6.1 の ID プールから、未認証の ID と、一時的な認証情報を受け取ります (期限の 5 分前まで、使い回します)。
- `DynamoDbReadings` が、`node` と `ts` の範囲で `Query` します (結果が 1 MB を超えるときは、`lastEvaluatedKey` で、続きを取り出します)。
- `ts` と `node` は、DynamoDB の予約語と重なるおそれがあるので、`expressionAttributeNames` で、別名にしています。
- `Query` は、1 回に最大 1 MB までです。10 秒に 1 件の 1 日分 (8,640 件) は、1 回で入らないことがあります。

## 8. これから

- 古いデータは、`expires_at` で、受信から 30 日後に消えます。長く残したいときは、TTL の期間 (4.1 の `2592000`) を長くするか、S3 にエクスポートします。
- 複数人で使うときは、Cognito のユーザープールによるログインに替えます。
