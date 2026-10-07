# 金鑰庫維運手冊

本文回答：維運如何建立、列出、新增、更新與刪除金鑰庫裡的機密與憑證，並讓 Engine 重載。語意與決策見 [ADR-019](../docs/stable/pipeline-engine/adr/ADR-019-typed-shared-resources.md) 第 6 與 12 點；Engine 的組態項目與失敗類別見 [04](../docs/stable/pipeline-engine/04-deployment.md)「組態與密鑰」；Docker 與 systemd 如何掛載金鑰庫見 [04-deployment-docker.md](../docs/stable/pipeline-engine/04-deployment-docker.md) 與 [04-deployment-systemd.md](../docs/stable/pipeline-engine/04-deployment-systemd.md)（可執行的檔案是本目錄的 `docker/compose.keystore.yaml` 與 `systemd/runline-engine-keystore.conf`）。

本文的每個指令都在 JDK 25.0.4.1 的 `keytool` 上實際執行過，輸出與此處一致（日期與指紋依當時而異）。範例中的機密與密碼都是佔位值。

## 規則（要求，不是建議）

1. **金鑰庫密碼只用密碼檔參數提供**：每個 `keytool` 指令用 `-storepass:file <檔案>`（與 `-deststorepass:file`、`-srcstorepass:file`、`-new:file`），不用 `-storepass <密碼>`，也不讓 `keytool` 向終端機詢問後以輸入回顯。密碼檔只有一行（`keytool` 與 Engine 都讀第一行），權限 `0600` 或 `0400`，只有操作的人與服務帳號（見平台指南）讀得到。理由：命令列參數會出現在行程清單與 shell 歷史。
2. **機密的值從標準輸入以管線提供**，不寫在命令列。用 shell 內建的 `printf` 不會在行程清單出現；要互動輸入時用 `read -rs`：
   ```
   read -rs SECRET; printf '%s\n' "$SECRET" | keytool -importpass ...; unset SECRET
   ```
   管線輸入時 `keytool -importpass` 只讀一行，不顯示提示，成功時沒有任何輸出。
3. **機密限可列印 ASCII**。非 ASCII 字元（連單一 é）會被工具對密碼項目的編碼破壞且無法還原；損毀無法在載入時全部偵測：被破壞後若落在可列印範圍，Engine 照常載入，在連線時呈現為認證失敗。因此新增或更新機密後，先對引用它的資源做「檢查」（`POST /api/v1/resources/{name}/check`，或 Console 的檢查）確認可用，再依賴它。此限制只針對機密項目，不適用於憑證與私鑰。
4. **更新一律是複製、改副本、原子替換、重載**（見下），不直接改正在使用的檔案。
5. 金鑰庫檔案只有 PKCS12 被接受（`-storetype PKCS12`；JDK 25 預設也是它）。別名會被 `keytool` 轉為小寫，Engine 以小寫比對。

下文以這些檔案為例，換成你的路徑：

```
STOREPASS=/etc/runline/keystore-password      # 密碼檔（Docker：deploy/docker/secrets/keystore-password）
KEYSTORE=/etc/runline/keystore/runline.p12    # 金鑰庫（Docker：deploy/docker/keystore/runline.p12）
```

## 建立金鑰庫與新增機密

沒有金鑰庫時，新增第一個機密就建立它（密碼檔須先存在，內容就是金鑰庫密碼）：

```
printf '%s\n' 'placeholder-database-password' | keytool -importpass \
  -alias db-main -keystore "$KEYSTORE" -storetype PKCS12 -storepass:file "$STOREPASS"
```

沒有輸出，結束代碼 0。新增別名用同一個指令換別名；別名已存在時失敗，必須先刪除（見更新）：

```
keytool error: java.lang.Exception: Secret Key not generated, alias <db-main> already exists
```

## 列出

```
keytool -list -keystore "$KEYSTORE" -storepass:file "$STOREPASS"
```

```
Keystore type: PKCS12
Keystore provider: SUN

Your keystore contains 1 entry

db-main, Oct 7, 2026, SecretKeyEntry,
```

`-list`（以及 `-list -v`、`-rfc`）不顯示機密的值。Engine 的看法（別名、類型、狀態、引用者）用管理員的 token：`GET /api/v1/secrets`。

## 更新機密（先刪後建加原子替換）

`keytool` 對既有別名的匯入會失敗，所以更新是「刪除、再匯入」；在副本上做，完成後以更名覆蓋換掉原檔，Engine 才不會讀到做到一半的檔案：

```
cd "$(dirname "$KEYSTORE")"
cp -p runline.p12 runline.p12.new                 # -p 保留擁有者與權限（服務帳號要能讀）
keytool -delete -alias db-main -keystore runline.p12.new -storepass:file "$STOREPASS"
printf '%s\n' 'placeholder-new-database-password' | keytool -importpass \
  -alias db-main -keystore runline.p12.new -storepass:file "$STOREPASS"
mv -f runline.p12.new runline.p12                 # 同一目錄內的更名是原子的
curl -s -X POST -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:8080/api/v1/secrets/reload
```

重載成功的回應（200）：

```
{"aliases":2,"changed":[{"alias":"api-key","usedBy":[]}]}
```

`changed` 列出新增、移除或內容有變的別名與引用它們的資源；`aliases` 是重載後的別名數。檢視這個結果：預期的別名在 `changed` 裡，然後對 `usedBy` 的資源做檢查。重載是整體的：失敗（422 `secret_store_unreadable`，`problem` 為 `file_missing`、`wrong_password`、`corrupt`、`wrong_format`、`unreadable`）時 Engine 維持原本記憶體中的內容，修好檔案再重載。內容有變的別名，其引用資源之後被取得的 run 用新值，進行中的 run 不受影響。

新增別名的流程相同，省略 `-delete`；刪除別名則省略匯入。

**Docker 的限制**：金鑰庫要以「目錄」唯讀掛載，不能只掛單一檔案：原子替換換掉的是檔案本身（新的 inode），單一檔案的掛載會繼續顯示舊檔（實測：替換後容器內仍看到舊內容、連結數為 0），Engine 重載讀到的還是舊的。`compose.keystore.yaml` 掛的是目錄。更名在宿主機上做，Engine 的掛載保持唯讀。

## 刪除

```
cp -p runline.p12 runline.p12.new
keytool -delete -alias api-key -keystore runline.p12.new -storepass:file "$STOREPASS"
mv -f runline.p12.new runline.p12
```

然後重載。仍引用該別名的資源，其檢查回報別名 `missing`。

## 憑證與私鑰（TLS 信任與 mTLS）

這兩種項目不受 ASCII 限制。Engine 側的使用由 [WI-52](../docs/stable/pipeline-engine/work-items/WI-52-tls-trust-and-mtls.md) 驗證；以下是 `keytool` 側的操作（同樣在副本上做，原子替換後重載）。

**信任內部 CA 或自簽的伺服器憑證**（匯入為受信任憑證項目；`-noprompt` 省略信任確認，指紋請先核對）：

```
keytool -importcert -noprompt -alias internal-ca -file ca.pem \
  -keystore runline.p12.new -storepass:file "$STOREPASS"
```

成功時輸出 `Certificate was added to keystore`。

**匯入含私鑰與憑證的 PKCS12**（mTLS 用戶端憑證）：

```
keytool -importkeystore -srckeystore client.p12 -srcstoretype PKCS12 -srcstorepass:file client.pw \
  -destkeystore runline.p12.new -deststoretype PKCS12 -deststorepass:file "$STOREPASS" \
  -srcalias client -destalias client -noprompt
```

**PEM 的私鑰與憑證先轉成 PKCS12 再匯入**（`client.pw` 是轉出檔的密碼檔；實測的是單一憑證的 PEM）：

```
openssl pkcs12 -export -inkey key.pem -in chain.pem -name client -out client.p12 -passout file:client.pw
```

**私鑰的保護密碼與金鑰庫密碼相同**：JDK 的 PKCS12 實作沒有獨立的金鑰密碼。指定不同的 `-keypass` 或 `-destkeypass` 時工具只警告並忽略它：

```
Warning:  Different store and key passwords not supported for PKCS12 KeyStores. Ignoring user-specified -keypass value.
```

實測：以忽略前的「另一個密碼」讀取該私鑰失敗（`UnrecoverableKeyException`），以金鑰庫密碼讀取成功。所以取得金鑰庫密碼就取得所有私鑰。

**列出類型、主旨、到期日與指紋**（輸出節錄）：

```
keytool -list -v -keystore "$KEYSTORE" -storepass:file "$STOREPASS" | grep -E '^Alias|^Entry type|^Owner|^Valid|SHA256'
```

```
Alias name: client
Entry type: PrivateKeyEntry
Owner: CN=runline-client
Valid from: Wed Oct 07 09:51:00 GMT-03:00 2026 until: Fri Nov 06 09:51:00 GMT-03:00 2026
	 SHA256: 1F:44:54:CE:64:B6:74:69:0B:20:D6:AF:3F:90:F5:E2:54:68:88:38:DF:A8:FF:68:60:67:1A:A7:AE:4C:96:C7
Alias name: internal-ca
Entry type: trustedCertEntry
Owner: CN=Example Internal CA
Valid from: Wed Oct 07 09:50:59 GMT-03:00 2026 until: Thu Oct 07 09:50:59 GMT-03:00 2027
	 SHA256: AA:1E:5A:7E:6D:F1:7B:38:B7:AB:3B:39:18:A0:55:B8:42:79:56:11:A2:31:30:E1:76:A0:E7:38:88:35:C9:BE
```

**到期前更換**：同機密的流程：複製、在副本上刪除舊別名並匯入新的、原子替換、重載。

## 選用：提高 PBE 迭代次數

預設 PKCS12 的保護是 `PBEWithHmacSHA256AndAES_256`，迭代 10000 次。提高次數增加離線暴力破解的成本，不改變檔案格式，Engine 與 `keytool` 讀取時不需任何設定。不做也可以運作。

實測（JDK 25.0.4.1，`keytool` 以 `-J-D...` 傳系統屬性，屬性名見 JDK 的 `conf/security/java.security`）：

| 屬性 | 作用 | 實測 |
|---|---|---|
| `keystore.pkcs12.keyPbeIterationCount` | 新寫入的機密項目與私鑰項目的保護 | 設 777777 後，檔案中該項目的 PBES2 參數為 777777（預設 10000） |
| `keystore.pkcs12.macIterationCount` | 整個檔案的完整性檢查（MAC） | 設 555555 後，檔案的 MacData 為 555555 |
| `keystore.pkcs12.certPbeIterationCount` | 憑證的保護 | 對只有機密項目的檔案沒有影響；對含憑證的檔案未實測 |

行為（皆實測）：

- 金鑰項目的次數**每次 `-importpass` 都要指定**；沒指定時，新的項目回到 10000。既有項目不會因別的項目的新增或刪除而改變。
- MAC 的次數在檔案建立時決定，之後的更新沿用它（建立時 555555，刪除再匯入後仍是 555555）。
- 要把既有金鑰庫整個提高（含已存在的項目與 MAC），用 `-importkeystore` 寫入新檔，兩個屬性都指定；讀回的機密值與原本相同：
  ```
  keytool -J-Dkeystore.pkcs12.keyPbeIterationCount=600000 -J-Dkeystore.pkcs12.macIterationCount=600000 \
    -importkeystore -srckeystore old.p12 -srcstoretype PKCS12 -srcstorepass:file "$STOREPASS" \
    -destkeystore runline.p12.new -deststoretype PKCS12 -deststorepass:file "$STOREPASS" -noprompt
  ```
  然後照常原子替換並重載。（實測用 600000 與 1000000；後者在 systemd 的 Engine 上載入與重載成功。）
- 讀取端的成本隨次數線性增加：`keytool -list` 整個行程（含 JVM 啟動）在 10000 次時約 0.15 秒，兩個屬性都設 2000000 時約 0.6 秒。
- 新建時：`printf '%s\n' '...' | keytool -J-Dkeystore.pkcs12.keyPbeIterationCount=600000 -J-Dkeystore.pkcs12.macIterationCount=600000 -importpass -alias db-main -keystore "$KEYSTORE" -storetype PKCS12 -storepass:file "$STOREPASS"`。

## 金鑰庫密碼的輪替

複製、以 `-storepasswd` 在副本上換密碼（新密碼也從檔案讀）、原子替換，然後**同時**更新密碼來源並重啟 Engine（[ADR-019](../docs/stable/pipeline-engine/adr/ADR-019-typed-shared-resources.md) 第 6 點：金鑰庫密碼的輪替需要同時更新密碼來源並重啟；沒有重載端點讀新密碼的保證，也未實測）：

```
cp -p runline.p12 runline.p12.new
keytool -storepasswd -keystore runline.p12.new -storepass:file "$STOREPASS" -new:file new-storepass
mv -f runline.p12.new runline.p12
# 把 new-storepass 的內容放進密碼來源（Docker secret 的檔案、systemd 的 /etc/runline/keystore-password），然後重啟 Engine
```

新密碼在副本上生效後，舊密碼就讀不開它（實測：舊密碼得到完整性檢查失敗）。

## 備份與還原

- 金鑰庫檔案與它的密碼**分開備份、分開保管**，也與資料庫的備份分開。取得其中一個不應等於取得機密。
- 還原資料庫但沒有金鑰庫時，Engine 照常啟動（沒有金鑰庫是允許的），引用別名的資源其檢查回報別名 `missing`（ADR-019；由 WI-46 與 WI-48 驗證）。還原金鑰庫檔案後重載。

## 限度

- 機密在 Engine 行程記憶體中以明文存在。
- 以環境變數（`RUNLINE_KEYSTORE_PASSWORD`）提供金鑰庫密碼時，同一 JVM 內的 unsafe pipeline 可以讀到它，也容易被子行程與診斷輸出帶出；所以用機密檔（`RUNLINE_KEYSTORE_PASSWORD_FILE`：Docker secret 或 systemd 憑證）。同一 JVM 內無法補強，對 unsafe pipeline 的授權要審慎（[ADR-006](../docs/stable/pipeline-engine/adr/ADR-006-unsafe-policy.md)）。
- Engine 發現金鑰庫檔案對其他使用者可讀（others 的讀取權限；群組可讀不算）時，啟動記錄警告（不含密碼）：`The keystore file is readable by other users; restrict it to the service account`。
