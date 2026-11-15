# BUILD — 適用・ビルド手順

comebackTwitter の jp18 仕様を再現する手順です。全体は2段階:

- **手順 A（バンドルで完結）**: Morphe でパッチバンドルを当てる。日本語化と基本構成はここまでで入る
- **手順 B（後処理）**: パッチ済み APK に対して、バンドルでは表現できない変更を dex / リソースレベルで追加する。jp18 仕様の残りはすべてここ

最後に **手順 C（署名）**。仕様の数値は [SPEC.md](SPEC.md) が正です。

> 前提: 本家 X の APK は各自で用意してください（配布していません）。署名鍵も各自のものを用意し、全バージョンで同一鍵を使い続けてください（鍵が変わると上書き更新できません）。鍵そのものは本リポジトリに含まれません。

## 必要なもの

| もの | 用途 |
|---|---|
| X 12.41.0-prod.01 の APKM（arm64-v8a、Android 10+ 向けバリアント） | パッチ対象 |
| Morphe CLI（morphe-desktop）または Morphe Manager | 手順 A |
| JDK（バンドル編集・ヘルパーのコンパイル用） | 手順 B の一部 |
| apktool / baksmali・smali、Android build-tools（zipalign / apksigner / aapt2） | 手順 B・C |
| Twitter 8.65.0-release.00 の APK | 手順 B9 の音源抽出用（各自で入手） |

## 手順 A — バンドルで完結する部分

同梱物:

- `patches/patches-3.75.0-jp.mpp` — 上流 piko-newx v3.75.0 バンドルに (1) 日本語リソース `values-ja-rJP/newx/strings.xml`（486 件）を追加し、(2) 拡張 dex（`extensions/newx.mpe` / `extensions/shared.mpe`）内のハードコード表示文字列 135 種を翻訳したもの
  - SHA-256: `d71823a80a3e069e45013a2416c59ec32cf035cdc79bdb8e2ebb2fa2e93902ba`
- `patches/options-jp.json` — パッチ選択とオプション（**Clone = 有効**（パッケージ名 `com.twitter.android.piko`）、**Restore Twitter branding = 有効**（ラベル「Twitter」）。「Server error logging」は無効のまま）

CLI の例:

```sh
java -jar morphe-desktop-<version>-all.jar patch \
  -p patches/patches-3.75.0-jp.mpp \
  --options-file=patches/options-jp.json \
  -o patched.apk \
  <自分の X 12.41.0-prod.01 .apkm>
```

期待結果: **48 パッチ適用 / 失敗 0**。この時点で、パッケージ名・ラベル・日本語化（リソース＋拡張文字列）は仕様どおりになる。

## 手順 B — 後処理（バンドル外の変更）

以下はパッチ済み APK への後処理です。各項目に「対象（dex / クラス・メソッド）」「変更内容」を記します。**全項目に共通する注意**:

- 変更は [SPEC.md](SPEC.md) §11 の安全原則（実証済みカテゴリのみ。コンポーザブルへの命令挿入・共有レジスタ反転は禁止）を守ること
- 各段階の後に zip エントリ単位の差分照合を行い、意図したエントリだけが変わっていることを確認すること
- 設定デフォルトの変更（B1 等）は「新規インストール / データ消去時に有効、保存済みの値があればそちら優先」という意味論です

### B1. 設定デフォルトの焼き込み（classes19 / classes21）

生成済み設定レジストリと各 Config クラスのデフォルト値を、[SPEC.md](SPEC.md) §4 の表どおりに書き換える:

- レジストリ側（`SettingsRegistry`）: `hide_add_tab_button=true`、`for_you_filtering.tab_hook=false`、`hide_who_to_follow=true`、`hidden_inline_actions` の `ViewCount` を初期選択、`custom_sharing_domain="twitter.com"`、`default_reply_sorting="Recency"`（選択マーカーも連動）、`hide_spaces_bar=true`、`dark_style="dim"`（選択マーカーも連動）、`hidden_drawer_items` の初期選択を §4 の12項目に（**リストは選択しない**）
- 拡張所有の設定（各 Config の初期化）: ナビの隠し項目 `"GROK"`、プロフィールタブの隠しリスト `"AffiliatedUsers,Reposts,SuperFollowers,Highlights,Articles"`
- ドロワーの「作業を自動化」（`RESOURCE_NAME_drawer_grok_bot_automate_work`）も初期選択に追加する
- コミュニティは非表示にしない（初期選択から外しておく）

### B2. フィーチャースイッチ上書きの初期シード（classes21）

`FeatureSwitchStore` の保存用文字列設定（キー `newx.advanced.feature_switches.overrides`）のデフォルト値を、以下の正規形 JSON にする:

```json
{"version":1,"overrides":[{"key":"android_history_enabled","type":"BOOLEAN","value":false},{"key":"x_lite_notifications_disabled_settings_callout_enabled","type":"BOOLEAN","value":false},{"key":"x_lite_notifications_permission_fullscreen_prompt_enabled","type":"BOOLEAN","value":false}]}
```

### B3. XApplication への patch override 登録（classes18）

`XApplication.onCreate` の注入ブロック（NewX 自身が登録している箇所）に、既存と同形式で [SPEC.md](SPEC.md) §5 の9件を登録する。特に `hometimeline_pinned_tabs_topics_enabled=false` が「追加」タイル消去の決定打（他の2件は設定 UI と描画様式用で、タイル本体には効かない）。

### B4. 旧式「追加」ボタンの消費側ゲート強化（classes18）

ホームタブの旧式追加ボタン（`com/x/home/tabbed/a` の当該コンポーザブル）が設定を読む箇所を、フォールバック `true` の2引数版 `getBooleanOrDefault(key, true)` に変える（どの解決経路でも隠れたままにするため）。

### B5. 用語置換とクレジット（resources.arsc）

文字列プールの**表示値だけ**を置換する（キー・書式指定子・ styled 文字列には触れない）:

- ja: リポスト→リツイート、引用ポスト→引用ツイート、ポスト→ツイート の順で置換（376 件）。除外: ポストシーズン、ローカライズ検証用フィクスチャ
- en（デフォルト構成）: 単語境界で Post/Posts/Posted/Posting→Tweet 系、Repost 系→Retweet 系（30 件）。除外: `Post or Town`（住所ラベル）
- `string/piko_newx_patch_version` の値を **`Piko %s · ミスティ`** に（デフォルト・ja 両構成）
- `string/joined_date` の ja 値を **`{date}からTwitterを利用しています`** に（1件）

### B6. プロフィールヘッダーの Grok / 検索アイコン除去（classes6）

プロフィールヘッダーの描画（`com/x/profile/header/b`）で、末尾アクション（grok / search）が2モードから合流する直後の地点で両者を null 化する。下流は「null なら描画しない」既存分岐のみで、戻る矢印・名前・⋮（overflow）には触れない。

### B7. ドロワー下段の上寄せ（classes17）

ドロワー合成（`com/x/main/drawer/k`）で、主項目直後の重み付きスペーサー（weight 1.0f）が下段を画面下端へ押し下げている。そのスペーサーへ渡す修飾子を空の Modifier に差し替え、下段（設定とプライバシー / Piko設定 / ヘルプセンター）が主項目の直下に流れるようにする。命令の挿入・ブロック移動はしない。

### B8. 自分プロフィールの「共有」ボタン除去（classes18）

プロフィールヘッダー状態を生成する箇所で、自分プロフィール分岐のプライマリアクション列が {共有, プロフィールを編集} で構築される。その列から共有（`button_action_share`）の要素だけを取り除き、{プロフィールを編集} のみにする。

### B9. 更新音（classes6）

1. Twitter 8.65.0-release.00 の APK（各自で入手）を展開し、`res/raw/psst2.ogg` と `res/raw/pop.ogg` を取り出す
2. 再生ヘルパー（新規クラス `Lpiko/jp8/Snd;`）を用意する: 2つの OGG をバイト配列として埋め込み、初回呼び出し時にキャッシュへ書き出して SoundPool（STREAM_MUSIC、音量 0.7）にロード。状態エッジでのみ再生し、初回観測はベースラインのみ、300ms 未満の連打は抑止、例外はすべて内部で握り潰す
3. フック: タブホーム PTR の Flow コレクタ（`com/x/home/tabbed/refresh/m` の `invokeSuspend`）の入口に、収集値の初回入口でのみ `Snd.edge(収集Boolean)` を呼ぶ呼び出しを挿入する。挿入部が書くレジスタは、直後の元命令が読み取り前に再代入するものだけに限ること（レジスタ安全性の証明が必須）
4. 結線の根拠: 8.65 本体でも開始遷移→psst2 / 完了遷移→pop であり、耳での同定とも一致する

※ 音源ファイルと、それを埋め込んだヘルパーのバイナリは**配布しません**。各自の 8.65 APK から抽出してください。

### B10. プル・トゥ・リフレッシュ（classes6 / classes18 / リソース）

1. **ロゴ枠の鳥**: `res/raw/x_refresh_logo.json` は、手順 A で「Restore Twitter branding」を有効にして当てると、**バンドル自体が同梱する単層・静止鳥ファイルにすでに差し替わっている**（バンドル内パス `twitter/bringbacktwitter/raw/x_refresh_logo.json`）。ファイルの構成は: キャンバス 240×240、単一レイヤー「X Rest」、鳥パス（22 頂点）のみ、レイヤー／グループの transform は identity、キーフレームなし（静止画）。このファイル自体は本リポジトリでは**配布しない**（鳥パスが X のロゴ意匠のため。各自の APK に手順 A で入るものを使ってください）。comebackTwitter の実ビルドとの差分は**塗り色のみ**: バンドル版の黒 `[0,0,0,1]` を Twitter ブルー **#1D9BF0**（`[0.114,0.608,0.941,1]`）に変える。手順 A 適用後の APK から当該 JSON を取り出して塗り色を書き換え、戻すこと（レイヤー名「X Rest」は実行時の色上書きキーパスの対象なので変えないこと）
2. **ヘッダー下降の等倍化**: プル時にヘッダー群を描画平行移動させる箇所（`com/x/home/tabbed/refresh/q` のヘッダー側分岐）にある除数リテラル `2.0f` を `1.0f` に置換する（定数1件のみ）
3. **円弧オーバーレイ**: `src/Arc.java` をコンパイル（`-source 8 -target 8`、d8 で dex 化）し、`Lpiko/jp15/Arc;` として classes6 に追加する。フックは3点（詳細は `src/README.md`）:
   - H1: `MainActivity.onStart` の invoke-super 直後に `Arc.attach(Activity)`（classes18、レジスタ書き換えなし）
   - H2: refresh 状態フィード（`refresh/g` の `invokeSuspend`）入口に `Arc.pull(l, fraction)`（classes6）
   - H3: B9 の音フックの直後に `Arc.edge(Boolean)`（classes6）
4. パラメータはすべて Arc 内の定数（[SPEC.md](SPEC.md) §10 の表が正）

### B11. ラベル消去の仕上げ（classes18 / classes7）

- **Made on X**: B3 の2フラグ（`media_upload_video_editor_edited_on_x_label_enabled` / `media_upload_photo_editor_edited_on_x_label_enabled`）で完了。追加のコード変更は不要
- **「Grokで作成」**: フラグ方式が存在しないため、判定役 `Lcom/x/ui/common/media/attribution/h;->p(...)`（classes7、平文コード）の**先頭4バイトだけ**を `const/4 v0, 0x0` ＋ `return-object v0` に書き換えるバイナリパッチを行う（対象命令の元のバイト列を施工前に必ず照合すること）。その後 dex の SHA-1 署名と Adler-32 チェックサムを再計算する。以降の本体は到達不能なデッドコードとして残る
  - 効果は「ドメインリストが空」というアプリ標準の未設定状態と等価。詳細・統合カードの両面でラベル行が合成されなくなる
  - 写真のユーザータグ付け・「AIで生成」開示は別系統のため影響しない

## 手順 C — 再署名

1. `zipalign -p 4` で整列
2. `apksigner` で**自分の署名鍵**により署名する（スキームは v3 のみでよい。対象は API 29+）
3. 検証: `apksigner verify`、`aapt2 dump badging` でパッケージ名 `com.twitter.android.piko` / versionCode `312410001` / ラベル `Twitter` を確認

## 検証の作法（毎回行うこと）

- 施工前の dex は、無変更で再構築するとバイト一致すること（ラウンドトリップ再現性）を先に確認してから編集する。再現しない dex（ツールチェーン製法が違うもの）は全面再構築を避け、B11 のような最小バイナリパッチに切り替える
- 各段階で、前段の APK との zip エントリ差分（CRC 比較）が「意図したエントリだけ」であることを assert する
- 最終 APK から編集対象 dex を再展開し、意図した変更だけが入っていることを再確認する

---

*本書は公開用の手順書です。数値・対象の正は [SPEC.md](SPEC.md)。版ごとの経緯は [CHANGELOG.md](CHANGELOG.md)。*
