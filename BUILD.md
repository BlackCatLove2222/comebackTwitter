# BUILD — 適用・ビルド手順

comebackTwitter の jp24.13.19 仕様を再現する手順です。全体は2段階:

- **手順 A（バンドルで完結）**: Morphe でパッチバンドルを当てる。日本語化と基本構成はここまでで入る
- **手順 B（後処理）**: パッチ済み APK に対して、バンドルでは表現できない変更を dex / リソースレベルで追加する。jp23 仕様の残りはほぼすべてここ（例外: B14 はバンドル表現が正規形、B15 は一部がバンドル表現可能。B17 は任意の上級手順）

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

- レジストリ側（`SettingsRegistry`）: `hide_add_tab_button=true`、`for_you_filtering.tab_hook=false`、`hide_who_to_follow=true`、`hidden_inline_actions` の `ViewCount` を初期選択、`custom_sharing_domain="twitter.com"`、`default_reply_sorting="Recency"`（選択マーカーも連動。**jp22 以降は B15 で `"Relevance"` に変更する**のが現行仕様）、`hide_spaces_bar=true`、`dark_style="dim"`（選択マーカーも連動）、`hidden_drawer_items` の初期選択を §4 の12項目に（**リストは選択しない**）
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

### B12. 純黒テーマ「ブラック」（jp19、classes21 / resources.arsc）

1. **動的色からの分離（classes21）**: `app/morphe/extension/newx/theme/DynamicColorPalette` の `isEnabled()` に、既存の dim / default 除外と並べて **contrast 除外**を追加する。新設ヘルパー `useContrastTheme()` は `useDimTheme()` / `useDefaultTheme()` と全く同型（現在の dark_style が "contrast" かを返す）。これで dark_style=contrast のとき、拡張のパレット経路は本体のネイティブパレット＋既存の色スタンプフックを通り、contrast 用の背景表（背景 #000000、浮いた面 #121314 等）がそのまま効く。動的色の副作用は Dim 時と同様に全て OFF になる。Material You（material）の経路には触れない
2. **改名（resources.arsc）**: 文字列プールの表示値だけを変更する — `piko_newx_theme_contrast` の en を "Material You (High Contrast)" → **"Black"**、ja を "Material You（高コントラスト）" → **「ブラック」**、同項目の summary（en / ja）も実際の意味（標準パレット＋背景だけ純黒）に書き換える。プール全数照合で、変更が対象の4文字列だけであることを確認すること

### B13. 「返信先」行の復活（jp20、classes19）

状態ビルダー `Lcom/x/urt/items/post/w5;->p(...)`（平文コード）で、decider `Lcom/x/ui/common/media/attribution/h;->A(...)` の戻り値が格納されたレジスタの**唯一の読み取り箇所**にある分岐 `if-eqz vXX, :cond_*` を、無条件の `goto :cond_*` に変える（変更は1命令のみ）。これで会話系モジュールでも replyContext の実モデルが UI 状態に常時渡る。decider 自体と呼び出しには触れない。null 代入ブロックは到達不能になるが、最小差分のために残置する。

※ 行が実際に表示されるかはサーバー応答のデータ依存（[SPEC.md](SPEC.md) §15）。通知タブでは表示され、ツイート詳細（スレッド）では現行のサーバー応答に材料が無いため表示されない。これは本手順の不備ではない。

### B14. 統合「外観」セレクタ（jp21、拡張ソース — **.mpp バンドルで表現可能**）

本項目は後処理ではなく、**パッチプロジェクト側の拡張ソース変更として .mpp バンドルにそのまま入れられる**のが正規形（X 本体のクラスへのバイトコードパッチは一切不要）。変更は3点:

1. **設定の登録**（`BuiltInSettings`）: 生成コードの設定登録と同型のブロックで、単一選択設定を登録する — `registerSingleChoice("newx.appearance.theme", "newx.theme.appearance", <タイトル文字列>, <概要文字列>, order=50, rebootApp=true)`、`configureSingleChoice("newx.theme.appearance", "system", true)`、選択肢 `light` / `dim` / `black` / `system`（`system` に既定印）。登録は contributor 経由で生成登録の後・freeze の前に実行されるため、生成グループ `newx.appearance.theme` への相乗りが成立する。ブロック全体を try/catch で包み、登録失敗が起動を壊さないこと（fail-open）
2. **同期の呼び出し**（`NewXSettingsHost.install()`）: 末尾に `AppearanceSync.sync()` の呼び出しを追加する。install() は `XApplication.onCreate` から `SettingsRegistry.load()` より前に呼ばれるため、同期書き込みは全コンシューマより先に着地する
3. **同期クラス**（新規 `AppearanceSync`、theme パッケージ）: `newx.theme.appearance` の現在値を読み、未設定または適用済みマーカー `newx.theme.appearance.synced` と同値なら何もしない（ワンショット意味論）。異なる場合のみ、[SPEC.md](SPEC.md) §14 の対応表どおりに本体 pref（`dark_mode_state` / `dark_mode_appearance`）と拡張の `dark_style` を書き込み、マーカーを更新する。全体を try/catch で包む

ラベル文字列は、バンドル形では専用の `piko_newx_appearance_*` をパッチのリソースに追加する。

**手ビルドで同等にする場合**: classes21 の smali 編集で同じ3点を施工できる。実物は `src/AppearanceSync.smali`（同期クラス全文）と `src/BuiltInSettings-registerAppearance.smali`（登録ブロックの抜粋）。手ビルドでは arsc への文字列追加を避けるため、ラベルの一部に既存文字列を流用している: ライト = `grok_settings_light`、端末の設定を使う = `settings_dark_mode_system`（ダークブルー / ブラックは `piko_newx_theme_dim` / `piko_newx_theme_contrast` を共用）。この場合の差分は classes21 のみになる。

### B15. 返信ソートの関連性ピン留め（jp22、classes19 / classes21）

ツイート詳細の返信ソートを関連性（Relevance）に恒久固定する（[SPEC.md](SPEC.md) §16）。変更は3点:

1. **登録既定値の復元（classes19、`SettingsRegistry`）**: B1 で焼いた `newx.timeline.default_reply_sorting` の登録既定を `"Recency"` → **`"Relevance"`** に戻し、選択肢マーカーも連動させる（Relevance を 0x1、Recency を 0x0 に）。この登録コードはバンドルの生成コード側にあるため、パッチプロジェクト側では**登録定義のデフォルト変更として表現できる**
2. **解決役の無条件化（classes21、拡張側）**: `ReplySortingResolver.getDefault()` の本体を、`"Relevance"` を即返す形に置き換える。従来の解決順（前回値 → 保存値／登録既定 → フォールバック）はすべてバイパスされ、全消費経路（classes19 のリポジトリ初期化シードと UI 状態初期化シード）が単一の `getEnumDefault` 経由で常時 Relevance を得る。**拡張ソースの変更であり、.mpp パッチとして表現できる**
3. **保存値のワンショット反転（classes21、拡張側・新規クラス）**: 起動時に一度だけ、保存済みの `newx.timeline.default_reply_sorting` を `"Relevance"` に書き換える同期クラス（jp21 の AppearanceSync と同型。マーカーで再実行を防ぎ、全体を try/catch で包む fail-open）を追加し、`NewXSettingsHost.install()` の末尾から呼ぶ。実効値は 2 で保証されるため、これは Piko設定の表示整合のためのもの。**拡張ソースの変更であり、.mpp パッチとして表現できる**

手ビルドの実物は `src/ReplySortingSync.smali`（同期クラス全文）と `src/ReplySortingResolver-getDefault.smali`（無条件化したメソッドの抜粋）。手ビルドでの差分は classes19 + classes21。

### B16. 引用数ピボット（jp23、classes6 / classes7）

他者の投稿で引用があるとき、詳細ヘッダ行のピボットを「N件の引用を表示」にする（[SPEC.md](SPEC.md) §17）。引用数のデータ自体はサーバー応答に含まれており、不足しているのは搬送と表示だけ。

1. **マッパへの Quote エントリ追加（classes6、`com/x/mappers/b;->e`）**: 応答モデル（postdetail のフラグメントモデル）の **quote_count フィールド（public final）を iget で直接読む**（アクセサは存在しない）。既存の件数付きエントリと同じ引数形でカウントエントリ（種別 Quote、件数、false, 4）を構築し、エントリ配列の**7番目**に追加する（配列生成の範囲を6要素分から7要素分へ拡張）。下流の搬送（エントリ列 → 会話マッパの汎用通過 → 焦点状態）は無変更で届く。**X 本体 dex への後処理**（バンドルでは表現できない）
2. **件数の取得とラベル差し替え（classes7、焦点状態の組み立て `a0->d` の非描画な前計算部）**:
   - 既存のリツイート数取得ループの直後に、同じエントリ列を走査して Quote エントリの件数を保持する第2ループを追加する。**`.locals` は増やさない**こと（引数レジスタの位置がずれて既存の命令形式が壊れる）。スクラッチに使うレジスタは、後段が依存するゼロ定数ペアを全出口で復元すること
   - 他者分岐のピボットラベルは、引用数 > 0 のとき文字列テンプレート `quote_tweets_with_count`（`{count}件の引用`）に件数を埋め込み「を表示」を連結したものに差し替える。0 件では従来の「引用を表示」、自分の投稿は「アクティビティを表示」のまま
   - ピボットの表示条件（showPivot）に **引用数 > 0** を OR で追加する（RT 0 件でも引用があれば出る）。行全体のゲート（返信数 > 1）には触れない

### B17. 詳細ヘッダ行のソートタブ除去（jp23 / jp23.1、classes7）— ⚠ 上級・リスクあり（任意）

詳細ヘッダ行の左側のソートタブだけを消す手順。**この手順は [SPEC.md](SPEC.md) §11 の安全原則に対する条件付き例外（カテゴリ (f)）であり、必須の検証をすべて満たせない場合は実施してはいけない**。省略した場合の形（タブは見えるが §16 のピン留めで実質無力）は jp22 形として仕様どおり成立する — それがフォールバックである。

対象は行の描画メソッド（`com/x/ui/common/media/attribution/e->d`）。構造: 外側の Row の中に、ソートボタンが**入れ子の Row ノード**としてインライン排出され、その後にピボットの置換グループが続く。ボタンは独立した composable 呼び出しではないため、呼び出し箇所の除去はできない。

**方式（goto スキップ）**:

1. ボタンの開始ノード呼び出し（startNode）の**直前に無条件 `goto`** を1命令挿入し、着地点ラベルをボタンの終了ノード呼び出し（endNode）の**直後**（ピボットのゲートの直前）に置く。消えるのはボタンのノード排出だけ。remember 群（シート開閉状態・ソートモード状態・クリックラムダ）と Modifier・測定ポリシーの計算は従来どおり実行され、スロット位置は変わらない

**⚠ 退避コピーの罠（jp23 初版の事故原因）**:

ボタンの排出区間の中では、ボタンの Text 呼び出しの引数シャッフルで低位レジスタが潰れるため、**後段（ピボット／シート）が必要とする値を高位レジスタへ逃がす退避コピー**が行われている。goto で区間を飛ばすと、この退避ごと消えて着地後のレジスタが未定義・型違いになり、**ART ベリファイアがクラスを拒否してアプリが起動不能になる**（jp23 初版の実例: `VerifyError … [0x247] copy-reference v8<-v44 type=Undefined`。検証はリンク時に走るため、詳細画面を開く前に起動時点で落ちる）。

**必須の対策 — 生存レジスタの再現（jp23.1 の形）**:

goto の**直前**に、退避コピーが作るはずだった値を**原本とまったく同じオブジェクト／値**で再現する命令列を置く。以下は本 dex（12.41 の当該メソッド）での実測対応表（12命令、純粋な move / const / sget のみ。composer は呼ばない）:

| # | 命令 | 再現する値 |
|---|---|---|
| 1 | `move-object/from16 v34, v19` | シート開閉状態（MutableState） |
| 2 | `move-object/from16 v36, v10` | ソートモード状態（MutableState） |
| 3 | `move-object/from16 v44, v12` | Composer の Empty センチネル |
| 4 | `move-object/from16 v45, v20` | 配置（アラインメント）単体 |
| 5 | `move-object/from16 v46, v11` | レイアウトポリシー単体 |
| 6 | `move-object/from16 v47, v14` | ノード用 Function0 単体 |
| 7 | `move-object/from16 v48, v15` | レイアウトポリシー単体 |
| 8 | `move-object/from16 v18, v11` | レイアウトポリシー単体（Text 引数スロット用、#5 と同源） |
| 9 | `sget-object v16, Landroidx/compose/ui/text/font/s;->m` | フォント単体（原本は区間内でロードするため再取得） |
| 10 | `const/4 v6, 0x0` | 原本の着地値 int 0 |
| 11 | `const/4 v7, 0x1` | 原本の着地値 true |
| 12 | `move-object/from16 v19, v33` | 行の Modifier。**必ず最後**（#1 の退避元が v19 のため、先に潰すと状態が壊れる） |

順序制約は #1 が #12 より前であることの1点のみ。ベースの X や対象メソッドが変わればレジスタ対応は変わるため、その場合は到達定義解析で対応表を作り直すこと（上表を鵜呑みにしない）。

**必須の検証（納品条件）**:

1. **レジスタ生存性パス**: 最終 APK から抽出した dex に対し、バイパス区間内で書かれる全レジスタについて「着地時点の到達定義」を機械解析し、着地後に読まれるものがすべて正しい型カテゴリで定義済みであること、センチネル・状態クラスのレジスタについては**値の出所（同一の sget 先・同一の退避元）まで原本と一致**することを確認する。**違反ゼロが合格条件**
2. グループ収支（構造呼び出しの開始／終了カウント一致）は**必要条件にすぎない**。jp23 初版はこの確認を通過しながら VerifyError で落ちた — 合格の本体は 1 の生存性である
3. 実機でのコールド起動確認（起動ログに `Verifier rejected class` が無いこと）と、詳細画面の開閉でクラッシュしないこと

手ビルドの実物抜粋は `src/HeaderRowSnippets.smali`（goto＋再現命令列と、B16 の前計算部抜粋）。

### B18. ゾンビフィルター（jp24 / jp24.2、拡張ソース＋トランスプラント）

1. 拡張ソース（piko-newx の extensions/newx）に ZombieFilter / ZombieSettings / ZombieSettingsSection を新規実装し、NewXTimelineFilter（filterItem への評価段追加）/ TimelineModelAccess / FilteredRepliesStore / PostFilterFragment を変更する。実物は [src/ZombieFilter.java](src/ZombieFilter.java) / [src/ZombieSettings.java](src/ZombieSettings.java)、仕様は [SPEC.md](SPEC.md) §18
2. 純ロジックは JVM ハーネスで単体テストする（jp24.2 時点で 147 件）
3. javac → D8（min-api 26）→ baksmali で smali 化し、配置する。**classes19 の NewXTimelineFilter 本体にはパッチ注入ブリッジがあるため、変更/新規メソッドのみを明示リスト（TAKE 方式）でトランスプラントし、本体バイト列へ一括差分を当てない**。classes21 はファミリー単位で差し替える（プレフィックス境界に注意 — PostFilterRule と PostFilterRuleAdapter のような前方一致の巻き込みを防ぐこと）
4. 互換性のため、旧シグネチャ互換コンストラクタ（Evaluation / MemoEntry）とマーカー型クラス（-IA）を保持する
5. 全クラス相互参照監査で、ベースライン比の新規欠落ゼロを確認する

### B19. データ使用量デフォルト（jp24.2、classes21）

1. 新規クラス `JpDefaults`（[src/JpDefaults.java](src/JpDefaults.java)）を classes21 に追加する
2. `NewXTimelineFilter.filterTimelineItems` の入口へ JpDefaults の呼び出しをトランスプラントする（B18 と同じ TAKE 方式）
3. JpDefaults はマーカー `newx.jp.data_usage_defaults_v1` が無いときだけ、本体 SharedPreferences へ video_autoplay="never" / image_quality="wifi_and_mobile" / video_quality="wifi_and_mobile" を書く

### B20. ダウンロード保存先デフォルト（jp24.3 / jp24.5、classes19 / classes21）

1. 新規クラス `JpMediaStoreDestination` / `JpRoutingDestination`（[src/](src/) の同名 .java）を classes21 に追加する
2. `NewXDownloadFolders` に defaultTreeUri / effectiveTreeUri（Context 版・引数なし版）を追加し、`NewXDownloader.install()` の writer を JpRoutingDestination に差し替え、`NewXDownloadTransfer` の complete() 公開を有効化する（抜粋は [src/DownloadSnippets.smali](src/DownloadSnippets.smali) 断片1〜3）
3. ゲート変更: NativeDownloadRouter のフォールバック条件を UNUSABLE のみにし、enqueue は effectiveTreeUri を使う
4. **classes19 の InlineDownloadButton 本体は出荷実物を保持**し、次の2点だけを smali 編集する: (a) needsFolderPrompt の本体を UNUSABLE 等値判定に差し替え（断片4）、(b) enqueueDownload 内の treeUri 呼び出し（1箇所）を effectiveTreeUri(MediaKind) 宛に変更（断片5）
5. ⚠ **本体の丸ごと再コンパイルは厳禁**。本体にはパッチ注入ブリッジ（createDownloadAction / isCompositionDownloadableMedia）が同居し、再コンパイルすると実処理がダミーに戻ってダウンロードボタンが消失する（jp24.3 で発生、jp24.5 で修正）。作業前にブリッジ走査を行い、差し替え後は2メソッドの実処理が残っていることを確認する

### B21. 引用行の常時表示（jp24.4、classes7）

1. プレゼンタ `Lcom/x/urt/items/post/h1;->b` の返信数ゲート false 側（:cond_465）の `const/16 v36, 0x0` を `0x1` に変える（1行、命令サイズ不変。抜粋は [src/QuoteRowSnippets.smali](src/QuoteRowSnippets.smali)）
2. v36 が FocalPostState の h 引数（showReplySorter）へそのまま流れ、他に再代入が無いことを確認する。同メソッド末尾のプレースホルダ全ゼロ初期化は対象外

### B22. ナビバーの DM アイコン（jp24.6、classes18 / リソース）

1. 未選択時: アイコンレジストリ `Lcom/x/icons/a;`（classes18）の clinit で、フィールド s1 に入る drawable id を 0x7f0804c7（ic_vector_chat_stroke）から **0x7f080546（ic_vector_email_stroke）** へ変える（1行。抜粋は [src/DmIconSnippets.smali](src/DmIconSnippets.smali)）
2. 選択時: `res/drawable/ic_vector_chat.xml` の pathData を自作の塗り封筒（[src/DmIconEnvelope.xml](src/DmIconEnvelope.xml)）に差し替える。AXML の編集は文字列プールの再構築による外科編集で行い、他チャンクはバイト不変に保つ。編集後は aapt2 dump xmltree で新しい pathData が読めることを確認する
3. 封筒ベクターは事前に描画して形を確認する（外形が email_stroke と揃っていること、フラップの V 字グルーヴが抜けていること）

### B23. ゾンビフィルター: スレッド主の記録ゲート修正（jp24.7、classes19）

スレッド主自身の返信が Z2 等で誤って隠れる不具合の修正（[SPEC.md](SPEC.md) §18）。

1. 原因: スレッド主の記録は会話経路で開いた場合にしか行われず、別経路で開いたスレッドでは「スレッド主の返信」免除が効かなかった
2. 修正: `NewXTimelineFilter.filterItem` の記録ゲートを `zombieThread || (verified 設定 && (filterTimeline || filterThread))` に拡張する**分岐命令1つの挿入**のみ（既存のチェック直後に条件分岐を1つ追加）。ゲートが偽の全ケースでは従来と同一動作になること、差分が当該ファイルの当該メソッドだけであることを確認する

### B24. ゾンビフィルター: Z11' 外国語ベイト（jp24.8 / jp24.9、classes19 / classes21）

1. 拡張ソースに判定 `ZombieFilter.isForeignBait`（[src/ZombieFilter.java](src/ZombieFilter.java)）と設定キー `foreign_bait`（[src/ZombieSettings.java](src/ZombieSettings.java)）を追加し、グルー `JpForeignBaitCheck`（[src/JpForeignBaitCheck.java](src/JpForeignBaitCheck.java)）を新規クラスとして classes21 に追加する
2. 評価器 `evaluateZombieReply` の所定位置（Z8 の後段）へ、グルー呼び出しの invoke を外科的に挿入する。**引数に渡す FilteredRepliesStore は、挿入地点で実際に store を保持しているレジスタから読むこと** — jp24.8 では前段ブロックが boolean に再利用済みのレジスタを store として渡し、VerifyError で起動不能になった。jp24.9 の納品形（退避先レジスタから死にレジスタへ移してから呼ぶ）を正とする
3. 施工後は必ず**レジスタ型ゲート**（「検証の作法」参照）を最終 dex に実行し、違反ゼロを確認する

### B25. ゾンビフィルター: Z12 同じ投稿への2回目リプライ＋上部バー常時表示（jp24.10、classes19 / classes21 / classes.dex）

1. Z12: グルー `JpDoubleReplyCheck`（[src/JpDoubleReplyCheck.java](src/JpDoubleReplyCheck.java)）を classes21 に新規追加し、設定キー `double_reply` を ZombieSettings に追加する。評価器への挿入は Z11' の直後（B24 と同じ外科方式＋レジスタ型ゲート必須）
2. 上部バー: classes.dex の `Landroidx/compose/material3/zf;->b(F)V` を「引数を無視して対象フィールドへ常に 0.0f を書く」実装に書き換える（[SPEC.md](SPEC.md) §23）。再アセンブルの往復差分で、実質差分が当該メソッドのみであることを確認する。※ 本手順の dex は jp24.10 の APK には含めず、jp24.11（B26 の施工時）に初めて統合した経緯がある。現行仕様としては B26 以降のビルドに含まれる

### B26. 指定アカウント非表示（jp24.11 / jp24.12、classes19 / classes21）

[SPEC.md](SPEC.md) §24 の実装。サーバーには一切触れない（ブロック API の呼び出しを入れないこと）。

1. 拡張ソースに `BlockedAuthorsStore`（[src/BlockedAuthorsStore.java](src/BlockedAuthorsStore.java)）を新規追加する。**シードは各自の対象リストで置き換える**（本リポジトリ同梱版は空）。ZombieFilter に理由定数 `BLOCKED_AUTHOR`、ZombieSettings にトグルキー `blocked_authors`、FilterConfig にスナップショット＋規則ビット 0x200 を追加する
2. 会話経路: `evaluateZombieReply` 内の除外条件（フォーカル／スレッド主／自スレッド返信）の後・Z1 の前に指定著者チェックを挿入する。**指定リストはホワイトリストより優先**させる。ゾンビマスター OFF でも効くよう、会話モジュールでは独立の評価（`evaluateBlockedReply`）も用意する
3. TL 経路: モジュールの子投稿（filterModule）とモジュール直下（filterModuleItem）に著者チェックを追加する。対象は直接の著者に加え引用元・リポスト元の著者。⚠ filterModuleItem 側は、挿入ブロックが使うレジスタが引数シャッフルで上書きされた後の値になっていないか必ず確認すること（jp24.11〜24.12 ではこのブロックがデッドコード化していたのを jp24.13 で発見・修正した）
4. 素のトップレベル投稿（jp24.12）: パス開始時に会話モジュールの entryId から会話ルート投稿 ID の集合を集めるヘルパー（`noteConversationRoots`、ThreadLocal 保持）を追加し、素の投稿には「会話ルート集合に含まれる投稿と文脈不明時は免除、それ以外は著者チェック」という形でチェックを入れる（フォーカル免除とセットでなければプロフィール対応にならない）
5. 本体（NewXTimelineFilter）はパッチ注入ブリッジを含むため丸ごと差し替え禁止。新規ヘルパーの追加移植＋挿入箇所の外科編集で施工し、レジスタ型ゲートと相互参照監査を行う

### B27. カテゴリ別ミュート（jp24.13 / jp24.13.1、classes19 / classes21）

[SPEC.md](SPEC.md) §25 の実装。

1. 拡張ソースに `MuteCategory` / `MuteCategoryStore`（[src/](src/) の同名 .java、プリセット5カテゴリのシード定義を含む）と設定 UI セクション（MuteCategorySettingsSection）を新規追加し、投稿フィルター画面のゾンビセクション直下に組み込む
2. 判定は filterModule / filterModuleItem / filterObject の層（指定アカウント判定と同じ層）で行い、ヒット時は理由 `MUTE_CAT_<ID>:<一致語>` を必ず FilteredRepliesStore に記録する。FilterConfig にはスナップショットフィールドと sameAs への同一性チェックを追加する（外科的 smali 編集）
3. ⚠ **sameAs 編集時の罠（jp24.13 初版の事故）**: 既存ブロックには引数レジスタを使い捨てにする比較（結果を boolean で受け直す）が含まれ得る。その後ろへ新しい同一性チェックを挿入すると、フィールドアクセスの対象が boolean 化したレジスタになって VerifyError になる。挿入前に各ブロックのレジスタ再定義を精査し、使い捨て先は死にレジスタへ逃がすこと。施工後は拡張済みレジスタ型ゲートで**変更した全クラスの全メソッド**を走査する（jp24.13.1 の納品条件）
4. 「フィルタされた返信」ダイアログには理由行が無かったため、理由表示そのものを追加する（ゾンビ系ラベル＋指定アカウント＋カテゴリ「名前」：単語 の形式）

### B28. 通知許可プロンプトの抑止（jp24.13.2、classes18）

[SPEC.md](SPEC.md) §26 の実装。B3 と同じ XApplication の登録ブロックへの追加で足りる:

1. `XApplication.onCreate` の patch override 登録ブロック（B3 の箇所）に、同一形式で `x_lite_notifications_permission_prompt_enabled` = false の登録を1ブロック追加する（const-string でキー、const/4 で false、registerPatchOverride 呼び出しの3命令。ブロック自身が使うレジスタは後続の元コードが読み取り前に再定義するものだけに限る）
2. jp2 由来のシード2件（fullscreen 変種用／設定コールアウト用）は表示可否を決めないため、本手順の代替にはならない。決定者の第一ゲートであるマスタースイッチを固定するのが要点

### B29. 設定への到達性（jp24.13.3、classes17 / classes20 / classes21）

[SPEC.md](SPEC.md) §27 の実装。

1. **スクロール修正（classes21、PostFilterFragment.onCreateView の2箇所のみ）**: (a) content の LinearLayout を ScrollView で包む（root の FrameLayout → ScrollView → content）。(b) ルール一覧コンテナの LayoutParams を weight 方式から**高さ固定 400dp**（Theme.dpToPx 経由）に変える — ScrollView 内では weight が潰れるため、一覧は枠内で内部スクロールさせる。マスタースイッチ／各セクション／空状態／＋ボタン（root へのオーバーレイ）の配線は変えない
2. **ドロワー入口（3 dex 協調、いずれも既存パターンの複製）**:
   - classes17: ドロワー合成（`com/x/main/drawer/k` の invoke）にある Piko設定行の注入ブロックの**直後**に、同形式の第2ブロックを挿入する。タイトル／クリックのプロバイダだけを差し替え、アイコン・レジスタ窓・スキップ先は同一にする（既存項目の並び替えはしない）
   - classes21: ComposeSettingsHook にタイトル供給（画面タイトル文字列 `piko_newx_post_filtering_title` を返す）とクリックハンドラを追加する。ハンドラは設定 Activity への Intent に extra `piko_newx.open_screen` = `newx.content.post_filtering` を付けて起動する
   - classes20: 設定 Activity（PikoSettingsActivity）の onCreate の新規作成パスで extra を消費し、正規遷移（openScreen）と同形のトランザクション（対象 Fragment の反射生成 → replace → addToBackStack → commit、フッター非表示を含む）を実行する。失敗時は捕捉してルート一覧表示にフォールバックし、再生成時は発火させない
3. 表示ゲートは Piko設定行のトグルと共通になる（仕様として受容済み）

### B30. 起動時の OS 通知許可リクエストの抑止（jp24.13.4、classes.dex）

[SPEC.md](SPEC.md) §28 の実装。前提として B28（マスタースイッチの false 固定）が施工済みであること。スイッチ false の側にいる「起動時の直接リクエスト」の起動ブロックだけを無条件スキップにする、**1命令の分岐書き換え**:

1. classes.dex を逆アセンブルし、`ai/x/complat/client/r` の `invoke(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;`（MainActivity の DI 配線）を開く
2. マスタースイッチ `x_lite_notifications_permission_prompt_enabled` の取得直後にある分岐 `if-nez v0, :cond_xxx` を **`goto :cond_xxx`** に書き換える（飛び先ラベルは自環境の逆アセンブル表記に合わせる）。スキップされるのは直後の起動ブロック3命令群（権限リクエスト用スコープの取得／識別子 0x1c で rw0 を捕捉する共有ラムダの生成／コルーチンの launch）のみで、合流後の経路はスイッチ true 時と同一になる。ブロック後に読まれるレジスタはすべて後段で再定義されることを確認してから施工する
3. **共有の権限リクエスト機構には触れない**: ワーカー（classes10 `com/x/android/permissions/c`）、共有ラムダ本体（classes11）、リクエスタ `rw0.e` は無変更。起動ブロックのコード自体も dex 内に残す（到達不能にするだけ）

検証: classes.dex は施工前に無変更再アセンブルが母体と**ビット一致**することを確認してから使う（一致しないツールチェーンでは施工しない）。`r.invoke` に対して編集前・編集後ともレジスタ型ゲート（「検証の作法」参照）を実行し PASS を確認する。残存スキャンとして、識別子 0x1c ＋ rw0 捕捉の組み合わせが全 dex で `r.invoke` のみに存在すること、起動ブロックが新 dex 内に無変更で残っていることを確認する。最終 dex の正規化 diff（ラベル改名・switch ペイロードのアライメント nop を除去）が分岐命令1行のみであること

### B31. 送信完了バナーのクラシック化（jp24.13.5、classes6）

[SPEC.md](SPEC.md) §29 の実装。リクエストモデルには色フィールドが無く、レンダラの色はテーマトークン固定・アイコンは単色ティントのため、**自前オーバーレイへの置き換え**が唯一の経路（jp15 の Arc と同じ「合成に触れず通常 View で出す」方式）:

1. **ヘルパークラスを用意する**（全文は [src/](src/) に同梱）: `BannerUi.java`（表示実体。decorView 上の通常 View で、色・寸法はクラス内定数 — SPEC §29 の値）を `javac -source 8 -target 8` → `d8 --min-api 29` でコンパイルし smali 化する。手書きグルー `SentBanner.smali` / `SentBanner$k.smali` / `SentBanner$Fin.smali`（提示役と同一の suspend 契約を実装する状態マシン＋結果受け渡し）と合わせ、パッケージ `piko/jp135` として **classes6** に追加する
2. **提示呼び出しの差し替え（classes6、1命令のみ）**: `com/x/composer/upload/v2/notifications/n` の `c` メソッド内で、送信完了ファミリーのリクエストをネイティブ提示 `n->e(api/a, api/n, ContinuationImpl)` へ渡している呼び出しを、`invoke-static Lpiko/jp135/SentBanner;->show(n, api/a, api/n, ContinuationImpl)` に差し替える。引数レジスタは元の呼び出しと同一に保つ。**n 内のもう1つの `n.e` 呼び出し（エラー系など別経路）は変更しない** — 置き換え範囲は送信完了ファミリーだけに限る
3. **Activity の供給（classes6、+1命令）**: jp15 の `Lpiko/jp15/Arc;->attach` の末尾に `invoke-static SentBanner.attach(Activity)` の転送を1命令追加する（WeakReference で保持。Arc 本体の動作は変えない）
4. ヘルパー側の契約（同梱ソースが正）: Activity と表示文言（リクエスト内の文言リソース）が取れ、他のバナーが表示中でなければオーバーレイを表示して suspend する。いずれか欠ければ**その場でネイティブ提示 `n.e` に委譲**する。表示中の追加送信は即座に AutoDismiss で完了する。終了コード（タップ = MessageClick／時間切れ等 = AutoDismiss）は提示役本来の結果処理がそのまま解釈するため、タップ時の遷移はネイティブと同一になる。コールバックは必ずメインスレッド投稿経由で非同期に届ける（呼び出し元のフレーム内では発火させない）

検証: classes6 は参照ツールチェーンでビット一致の往復にならないため、B29 の classes17 / 20 と同じ**意味的同一性**（再アセンブル dex の逆アセンブルが母体ツリーと diff 0 件）を確認してから施工する。手書き smali の全メソッドと差し替え先 `n.c` に対して、最終 dex 上でレジスタ型ゲートを実行し PASS を確認する（既知の偽陽性が2種ある: Arc.attach の指摘は母体でも同一に出る既存のもの、BannerUi の `const-wide/16` 由来の指摘は d8 の正規出力に対するチェッカのモデル化漏れ — いずれも母体との比較で「増えていない」ことを確認する）。編集ツリーの diff が n.smali（1命令）／Arc.smali（+1命令）／新規 `piko/jp135/` のみであること。前段 APK との CRC 差分が classes6.dex ＋ META-INF のみであること

### B32. コンポーザーのブースト・アップセルの抑止（jp24.13.6、classes18）

[SPEC.md](SPEC.md) §30 の実装。B28 と同じ XApplication の登録ブロックへの追加で足りる:

1. `XApplication.onCreate` の patch override 登録ブロック（B3 / B28 の箇所）で、B28 のブロックの直後・NewXUtils.initialize の直前に、同一形式で `android_composer_boost_upsell_enabled` = false の登録を1ブロック追加する（const-string でキー、const/4 で false、registerPatchOverride 呼び出しの3命令。ブロック自身が使うレジスタは後続の元コードが読み取り前に再定義するものだけに限る）
2. このフラグの読み手はコンポーザーのブースト状態機械（classes13）の表示決定点ただ1箇所であり、false はコード既定値のため副作用は当該アップセルの抑止に限られる。自分の投稿の「ブースト」ボタン等の同名に近い面は**別フラグ群**（`android_tweet_promote_button_boost_cta_*`）であり、本手順では対象にしない（SPEC §30）

検証: classes18 は施工前に無変更再アセンブルが母体とビット一致することを確認する。XApplication の全メソッドに対してレジスタ型ゲートを実行し PASS を確認する。陽性対照として、新しい classes18 にフラグ文字列が1件存在することを確認する

### B33. Explore ランディングのニュース／トレンド除去（jp24.13.7、classes19）

[SPEC.md](SPEC.md) §31 の実装。セクションを制御するフラグは存在しないため、既存のタイムラインフィルタと同じ入口で落とす:

1. **フィルタ本体（classes19）**: `NewXTimelineFilter` に静的メソッド `filterExploreItems(UrtTimelineType, List)` を新設する（Java で書き d8 → smali 移植。依存は既存のモデルアクセサと java.util のみ）。EXPLORE_FOR_YOU のときだけ、Trend（`items.y1`）と EventSummary（`items.m0`）を素・ラッパー（`items.u0`）・モジュール子（`items.a1` の子）の全形で除去する。部分的に残ったモジュールはヘッダー／フッターを保持してコピー再構築し、完全に空になったモジュールは落とす。無変更なら**入力と同一の List オブジェクト**を返し、全体を例外ガード（フェイルオープン）する
2. **フック（classes19、2命令）**: URT 成功結果の組み立て `com.x.urt.g2.<init>` の先頭に `invoke-static {p1, p2}, filterExploreItems` ＋ `move-result-object p2` を挿入する（既存の filterTimelineItems フックより前）。p1＝タイプ、p2＝アイテムリストで、後続の元コードは p2 をそのまま読む

検証: classes19 のラウンドトリップ往復がビット一致することを先に確認する。最終 dex の正規化 diff が `NewXTimelineFilter` と `g2` の2ファイルのみであること、レジスタ型ゲート PASS、CRC 差分が classes19.dex のみであること。JVM ハーネスで除去形（素／ラッパー／モジュール子／部分残存／全空／無変更時の同一オブジェクト）を検証する

### B34. Explore カテゴリタブへの拡張と広告バナー除去（jp24.13.8、classes6 / classes19）

[SPEC.md](SPEC.md) §32 の実装:

1. **タイプ割当のリラベル（classes6、1命令＋ラベル1つ）**: ホームタブのページ組み立て `com/x/home/tabbed/c` の非公開メソッド `c(Object, Object)` が、タイプ割当の唯一の地点。セグメント評価の分岐 `if-eqz v8, :cond_43` の飛び先を `:cond_44` に付け替え、EXPLORE_FOR_YOU の代入直前に新ラベル `:cond_44` を置く。これで `page == "explore"` の全セグメントが EXPLORE_FOR_YOU になり、B33 の除去（と B35 のキープフィルター）がカテゴリタブにも効く。page が "explore" 以外の経路は無変更であることを分岐表で確認する
2. **広告バナーの除去（classes19）**: B33 の `filterExploreItems` に、既存ヘルパー `isTimelineRtbImageAd` 経由の `items.p1`（UrtTimelineRtbImageAd）除去を3点（素／モジュール子／再構築時）追加する。SSP は既存 p1 の解決・印付けのみでアイテムを生成しないことを本体コードで確認済み — 除去は g2 地点で完結する

検証: classes6 の正規化 diff が `com/x/home/tabbed/c.smali` のみ（分岐1＋ラベル1）であること。両 dex のレジスタ型ゲート PASS。CRC 差分が classes6.dex ＋ classes19.dex のみであること

### B35. おすすめキープフィルター（jp24.13.9、classes19 / classes21）

[SPEC.md](SPEC.md) §33 の実装。新規ファミリーは classes21、入口は B33 の `filterExploreItems`:

1. **保存層・設定（classes21、新規）**: `postfilter/RecommendKeepStore`（永続 JSON `newx.content.recommend_keep`、schema 1。カテゴリ定義＝技術系／動物の語彙・オプション。フォロー済み著者 ID キャッシュ上限 5,000・期限なし、診断カウンタ、判定メモ）と `postfilter/RecommendKeepSettingsSection`（「ツイートフィルター」画面へのセクション。組み込みは PostFilterFragment への呼び出し追加）を新設する。全文は [src/](src/) に同梱
2. **判定役（classes21、新規）**: `timeline/RecommendKeepGate`。`isEnabledFor(type)`（対象は FOR_YOU / EXPLORE_FOR_YOU のみ、マスター ON かつ診断 TRUSTED のとき真）、`harvestFollowed(type, items, access)`（FOLLOWING / RANKED_FOLLOWING 応答から著者 ID を収穫）、`evaluatePost(...)`（フォロー済み→キープ／興味カテゴリ一致→キープ＋理由なし／それ以外→ `REC_KEEP_MISS` で落下）
3. **モデルブリッジ（classes21）**: `TimelineModelAccess` に著者ナビゲーションを追加する。既存の汎用探索は4段までしか降りないため、アイテム→投稿結果→文脈付き投稿→正規投稿→著者→最小ユーザーの**5段を決定的に辿る**専用経路と、表示名取得 `getPostAuthorNameForFilter`（旧ブリッジはタイムラインアイテムから null を返すため新設）、友情情報（`isFollowedByMe` / `isSuperFollowedByMe`）、正規投稿のメディア有無・言語のアクセサを用意する。すべて反射＋例外ガード
4. **入口（classes19）**: B33 の `filterExploreItems` 内で、対象タイプなら収穫（フォロー中タイプ）またはゲート判定（おすすめタイプ）を行う。落下は FilteredRepliesStore に理由付きで記録し、「フィルタされた返信」ダイアログに `REC_KEEP_*` のラベル分岐を追加する

検証: 新規3クラスの JVM ハーネス（分類・診断の遷移）全 PASS。classes19 / classes21 の正規化 diff が対象ファイルのみ、レジスタ型ゲート PASS、CRC 差分が classes19.dex ＋ classes21.dex のみ。診断が想定外に DEAD へ落ちる経路が無いことをハーネスで確認する（この診断定義自体は B44 で再定義される）

### B36. （公開版では削除）

公開版ではこの手順を提供しない（SPEC §34 の注記参照）。

### B37. 英字トークンの単語境界マッチと種の見直し（jp24.13.11、classes21）

[SPEC.md](SPEC.md) §35 の実装。`RecommendKeepStore` の語句マッチャーのみ:

1. マッチャーの一致判定に境界条件を入れる: 純 ASCII 語句は、一致位置の前後の文字が ASCII 英数字でない場合にのみ一致とする。非 ASCII 語句は従来どおり部分文字列
2. 技術系の種から "AI" を削除し、schema 1→2 の移行で永続済み "ai" を一度だけ除去する（移行ガードは schema 2 を明示的に対象にする — 後続のバージョン定数に引きずられないこと）

検証: JVM ハーネスで境界ケース（単語内包・CamelCase ハッシュタグ・複数形）を確認する。CRC 差分が classes21.dex のみ

### B38. カテゴリプリセット追加 第1弾（jp24.13.12、classes21）

[SPEC.md](SPEC.md) §36 の実装。`MuteCategoryStore` のみ（設定 UI・理由ラベル・判定側は汎用実装のため無変更）:

1. `presetCategories()` に `gender`（ジェンダー・男女論、17語）/ `nightwork`（水商売、21語）/ `intlfeud`（日韓・日中対立、25語）を追加する。種の全文は同梱ソースが正
2. 読み込み時の移行に append-missing を入れる: 保存済み文書に無いプリセット ID があれば既定値で末尾に補う（冪等、ユーザー編集は無変更）。焦点免除等の判定経路は B27 の既存経路をそのまま使う

検証: JVM ハーネスで新規プリセットの存在・既定値・移行の冪等性・編集済み文書の不変を確認する。CRC 差分が classes21.dex のみ

### B39. 宗教・移民プリセットと多言語種拡張（jp24.13.13、classes21）

[SPEC.md](SPEC.md) §37 の実装。`MuteCategoryStore` のみ:

1. プリセット `religion`（宗教・移民、25語＝日本語15＋英語10）を追加する（B38 の append-missing で既存文書にも入る）
2. 既存プリセットの種を拡張する: gender 17→34、nightwork 21→23、intlfeud 25→40（英語・韓国語・中国語の語彙を含む）。拡張前の種を参照定数として保持し、移行時に「保存済み語彙が旧種と**完全一致**する場合のみ新種へ置き換える」（トグル等の他フィールドは保持）。編集済みリストには一切触らない

検証: JVM ハーネスで完全一致時のみ置換・編集済みは無変更・冪等を確認する。CRC 差分が classes21.dex のみ

### B40. 評価ポップアップの抑制と「地域・原発」プリセット（jp24.13.14、classes18 / classes21）

[SPEC.md](SPEC.md) §38 の実装:

1. **評価ポップアップ（classes18）**: B32 と同じ登録ブロック（B32 の直後・NewXUtils.initialize の直前）に、`xlite_satisfaction_enabled` = false の登録を1ブロック追加する（3命令、B28 / B32 と同型）。フラグ文字列は classes7 にのみ存在し、読み手は満足度エンジンのコンストラクタ1箇所（エンジンの唯一の生成地点）であることを走査で確認する。Play のレビューライブラリ（classes10）には触らない
2. **プリセット（classes21）**: `MuteCategoryStore` に `regional`（地域・原発、12語）を追加する（B38 の append-missing 経由）

検証: classes18 は B32 と同じ作法（往復ビット一致、XApplication 全メソッドのレジスタ型ゲート、フラグ文字列の陽性対照）。CRC 差分が classes18.dex ＋ classes21.dex のみ

### B41. 検索候補の指定アカウント非表示と「男女分断」（jp24.13.15、classes6 / classes21）

[SPEC.md](SPEC.md) §39 の実装:

1. **ヘルパー（classes21、新規）**: `postfilter/TypeaheadAuthorFilter`（全文は [src/](src/) に同梱）。`filterMixed(List)` / `filterUsers(List)` の2入口を持ち、トグル `ZombieSettings.blockedAuthorsEnabled()` と `BlockedAuthorsStore` のスナップショットだけで判定する。要素からの ID 取得は TimelineModelAccess と同じ反射方式（混合リストでは候補ラッパーのクラス名を見てユーザー型だけを対象にし、クエリ候補等は素通し）。全体を try/catch で包み、該当なし・トグル OFF・例外時は**入力と同一の List オブジェクト**を返す
2. **フック（classes6、4箇所×2命令）**: タイプアヘッド集約リポジトリ `Lcom/x/repositories/typeahead/f;` の、ユーザー候補を返す4メソッド（混合リストの `a`、メンション補完の `b`、ユーザー検索の `c`、支払いピッカーの `f`）の**成功出口**に `invoke-static` ＋ `move-result-object` を挿入する。ハッシュタグ系のメソッド（`d` / `e`）は変更しない
3. **種の追加（classes21）**: B39 と同じ完全一致移行で、gender 種に「男女分断」を1語追加する（34→35語）

検証: classes6 の正規化 diff が `f.smali` のみ（挿入8命令＋ラベル付け替えのみ）であること。ヘルパーと f の全メソッドにレジスタ型ゲート PASS。JVM ハーネスで混合／ユーザーのみの除去・クエリ候補の素通し・同一オブジェクト返却を確認する。CRC 差分が classes6.dex ＋ classes21.dex のみ

### B42. カテゴリ照合への展開URL追加と「誘導・釣り」プリセット（jp24.13.16、classes19 / classes21）

[SPEC.md](SPEC.md) §40 の実装:

1. **URL アクセサ（classes21）**: `TimelineModelAccess` に `getPostExpandedUrls` / `getPostQuotedExpandedUrls` を追加する。本文テキストと同じエンティティリストから URL エンティティリストを取り、各要素について expandedUrl を優先し、空なら displayUrl にフォールバックする。要素単位＋全体の例外ガード付き（失敗時は空リスト）。⚠ このクラスは全体再コンパイルで出荷済みクラスと意味差分が出る（ツールチェーン差）ため、新規メソッドだけをコンパイル済み smali から移植する
2. **照合の拡張（classes19 / classes21）**: classes19 の `evaluateMuteCategory` が、本文スコープ有効時に本文 URL、引用スコープ有効時に引用 URL を取得し、`MuteCategoryStore.Snapshot.findMatch` の6引数版へ渡す（4引数版は null リストで委譲＝従来動作と同一）。照合側はテキストの後に URL を同じ正規化で走査する。晒し検出器は URL に適用しない。キーワードフィルタと B35 のキープフィルターには触れない
3. **プリセット（classes21）**: `MuteCategoryStore` に `bait`（誘導・釣り、フレーズ6＋ドメイン8）を追加する（B38 の append-missing 経由）。フレーズは長い方を先に置く

検証: JVM ハーネスで URL 照合（本文／引用、大文字小文字、t.co 単体では誤爆しない、無害ドメインは通過、スコープ OFF 時は対象外、理由形式）を確認する。両 dex の正規化 diff が対象ファイルのみ、レジスタ型ゲート PASS、CRC 差分が classes19.dex ＋ classes21.dex のみ

### B43. 指定アカウント ID エディタと JSON バンドル入出力（jp24.13.17、classes21 ほか）

[SPEC.md](SPEC.md) §41 の実装:

1. **ストア側の検証ヘルパー（classes21）**: `BlockedAuthorsStore` に `normalizeIdInput` / `idInputError`（数値のみ・19桁まで・先行ゼロ正規化・全ゼロ不可）と `replaceAll`（インポート用。SEEDED フラグを必ず立てる）を追加する
2. **ID エディタ（classes21）**: `ZombieSettingsSection` の「指定アカウントを非表示」トグルの下に「指定アカウントを編集（N件）」ボタンを追加し、ダイアログで ID 一覧（数値昇順、各行に削除）＋数値入力＋追加を実装する。書き込みはストア経由で即時反映（再起動不要）。ダイアログを閉じたら件数ラベルを更新する
3. **バンドル入出力（classes21、新規）**: `postfilter/FilterBundleImportExport`（全文は [src/](src/) に同梱）。エクスポートは muteCategories / keywordRules / recommendKeep（投影のみ。`RecommendKeepStore.exportProjection()` を新設し、フォローキャッシュと診断状態は出さない）の3セクションで、**blockedAuthors は含めない**。インポートは2段階検証→適用で、muteCategories は全置換＋プリセット補完＋なりすまし強制補正、blockedAuthors は手書きファイル分のみ受け付け（無効エントリはスキップして件数報告）
4. **画面側の配線（classes21）**: 「ツイートフィルター」画面の末尾にエクスポート／インポートボタンを置き、ファイル（作成／開く、要求コードは FeatureSwitch 系と重ならない値、上限 1MB）とクリップボードの両経路を用意する。結果は `NewXSettingsActivity.onUnhandledActivityResult` のチェーンに1行追加して受ける。ファイルインポート後の画面反映のため、`PostFilterFragment` は3セクションを個別コンテナで保持し onResume で再構築する（B29 と同じ外科モデル。レジスタ型ゲート必須）

検証: JVM ハーネスでエディタ検証・seed-once・書き出し形状（blockedAuthors / followedCache / diag が文字列として現れないこと）・往復・セクション単位の失敗分離・なりすまし補正を確認する。レジスタ型ゲートは変更した全クラス（PostFilterFragment の外科メソッド含む）で PASS。CRC 差分が classes21.dex のみ

### B44. キープフィルター診断の修正（jp24.13.18、classes21）

[SPEC.md](SPEC.md) §42 の実装。B35 の診断定義の差し替え:

1. **ゲート側**: `RecommendKeepGate.harvestFollowed` の診断ヒットを「友情フラグが真」から「**著者 ID が収穫できた**」に変える。ゲートはパスごとの増分（サンプル数・ヒット数）だけを数え、ストアへ1回だけマージする（ゲート側の読み書き競合を持たない）。メソッド単位のトランスプラントで施工し、クラス内の他メソッドはバイト同一を保つ
2. **ストア側**: 診断更新を同期メソッド `recordHarvestSamples(deltaSamples, deltaHits)` に集約する（サンプル40到達で ヒット>0 ? TRUSTED : DEAD を決定。到達後は単調で、後着のマージは無効）。旧 `updateDiagnostic` は削除する（呼び出し元がゲートだけであることをツリー走査で確認）
3. **移行**: schema 2→3。読み込み時に旧 DEAD を一度だけ UNKNOWN へ戻す移行を入れ、読み込み後の再保存で「一度きり」を保証する。schema 2 用の既存移行のガードは対象 schema を明示する形に固定する
4. 設定セクションの DEAD 文言を新定義に合わせて1箇所変更する

検証: JVM ハーネスで旧バグ再現（フラグ全滅でも ID が取れれば TRUSTED）・真の停止（ID ゼロで DEAD が永続）・並行マージの単調性・移行の一度きりを確認する。最終 dex の正規化 diff が対象3ファイルのみ、レジスタ型ゲート PASS、CRC 差分が classes21.dex のみ

### B45. キープフィルターのページ単位プリスキャン fail-open（jp24.13.19、classes19 / classes21）

[SPEC.md](SPEC.md) §43 の実装:

1. **ゲート側（classes21）**: `RecommendKeepGate` に `hasFollowedSignal(items, access)` を追加する。収穫と同じ投稿収集でページを走査し、著者が「フラグ TRUE またはキャッシュ命中」のいずれかなら真。例外時は偽（＝信号なし）を返し、呼び出し側を素通しに倒す。メソッド単位のトランスプラントで施工する
2. **フィルタ側（classes19、8命令）**: B33 の `filterExploreItems` で、ゲート有効判定（`isEnabledFor`）の直後にプリスキャンを挿入する: ゲート有効かつ信号なしなら、そのページのゲート適用自体を取りやめる（落下・記録・判定メモのいずれにも触れない）。Explore のニュース／トレンド／広告除去（B33 / B34）は並行して従来どおり行う

検証: JVM ハーネスで信号あり（従来動作）／信号ゼロ（同一オブジェクト返却・記録ゼロ・メモ不変）／フラグのみ信号／複数アカウントの和集合キャッシュ挙動／プリスキャン例外時を確認する。両 dex の正規化 diff が対象ファイルのみ、レジスタ型ゲート PASS、CRC 差分が classes19.dex ＋ classes21.dex のみ

### B46. おすすめキープフィルターのアカウント別化（jp24.13.20、classes19 / classes21）

[SPEC.md](SPEC.md) §44 の実装:

1. **閲覧者ブリッジ（classes19、本体側）**: タイムライン応答の構築クラスが保持する閲覧者 ID を読む合成メソッドは既存のものを再利用し、新規の通知メソッドを追加する。読み取った ID を拡張側 `CurrentViewer.notifyAccount(J)` へ渡す。呼び出しはコンストラクタ内の安全な位置（閲覧者フィールドの初期化直後、`this` が有効で以降の全利用に先行する地点）に1命令挿入する。⚠ コンストラクタ末尾は `this` が別オブジェクトで上書き済みの場合があり、その位置への `invoke-virtual {p0}` は VerifyError になる — 必ずフィールド初期化直後に入れること。ブリッジ本体にも自己回復用の通知を埋め込み、コンストラクタ経由でない生成でも閲覧者が伝わるようにする
2. **フィルタのスレッド化（classes19）**: ゲート分岐の頭で `CurrentViewer.accountKey()` を1回だけ解決し、収穫・有効判定・プリスキャン・分類の全呼び出しへ明示的に渡す。レジスタ飽和したメソッドでは引数を連続窓へステージングして range 形式に変換する（ステージング先の生存範囲は生存解析で確認すること）
3. **ストア／ゲート／設定（classes21）**: `RecommendKeepStore` を schema 4 に上げる（フォローキャッシュ・診断をアカウント別パーティション配下へ、LRU 上限 8）。移行は旧和集合データを**破棄**して再収穫する（パーティションへの種入れはしない）。判定メモのヒット条件にアカウントキーを加える。未判定（キー null）ではゲート完全 OFF・収穫 no-op とし、和集合へのフォールバックは作らない。設定の診断表示・リセットは現在のアカウントに対して行う

検証: JVM ハーネスで移行（破棄・一度きり）／分離／未判定（収穫で文書自体が作られないこと）／判定メモの漏れ／比率の分離／LRU 追い出し／入出力（export にアカウント別データが含まれないこと）を確認する。両 dex の正規化 diff が対象ファイルのみ、レジスタ型ゲート PASS

### B47. 設定スクロール修正／単語一括追加／フォロー中「最新」固定／ページ収量ガード（jp24.13.21、classes6 / classes19 / classes21）

[SPEC.md](SPEC.md) §45 の実装:

1. **単一スクロール化（classes21、PostFilterFragment）**: ルール一覧の固定高さ ListView を廃し、ScrollView 内の縦コンテナにアダプタの行 View を並べる形へ変える。件数と行の出所を分けないこと（B50 の事故の前提）。⚠ 手書きメソッドの `.registers` はパラメータレジスタとローカルの重なりを必ず確認する — ループ内の定数代入が `this` を潰すと複数件で確定的に落ちる（本ビルドの監査で実際に検出した）
2. **単語一括追加（classes21）**: 分割ユーティリティ `BulkPhraseInput.split`（区切り `[,、，\r\n]+`、全角スペース含む trim、初出順で重複排除）を新設し、カテゴリ別ミュートとおすすめキープ（カテゴリ・除外の両方）の単語エディタに組み込む。単一トークン時は従来の検証経路をそのまま使う
3. **フォロー中「最新」固定（classes6、本体側）**: フォロー中タブ専用リポジトリのコンストラクタに、ソート種別を「最新」へ上書きする1命令を追加する（super 呼び出し直後、フィールドへ使う前）。保存済み設定には触れない
4. **ページ収量ガード（classes19 / classes21）**: B45 のプリスキャンの後にゲートのドライラン収量判定を呼ぶ。定数は最小ページ投稿数 8・保持上限 3。ドライラン前に判定メモとアカウント別比率状態をスナップショットし、立てば復元する。ストア側に比率状態のスナップショット／復元メソッドを2つ追加する

検証: JVM ハーネスで分割・収量（救済／適用／境界）・アカウント別回帰を確認する。CRC 差分が対象 dex のみ、レジスタ型ゲート PASS

### B48. メディア系3機能（jp24.13.22、classes17 / classes19 / classes20 / classes21）

[SPEC.md](SPEC.md) §46 の実装:

1. **(A) 完了表示の一本化（classes19）**: ダウンロード状態の報告メソッド本体を、アプリ内通知の `show()` 呼び出しのみに差し替える（成功時は Toast なし、facade 不在時のみ Toast）。エンジン側の ConflictPolicy には触れない
2. **(B) 保存メディアビューア（classes17 / classes19 / classes20 / classes21）**: 新規画面 `SavedMediaFragment`（配置は classes21）＋記録インデックス `SavedMediaStore`。MediaStore 照会とインデックスの和集合で列挙し、サムネイル・画像表示・動画再生（素の VideoView）を行う。ダウンロード宛先の完了出口でインデックス記録を呼ぶ。認証はフレームワークの生体認証 API で毎回必須とし、使えない端末では開かない（fail-closed）。入口は Piko 設定のカスタム画面登録＋ドロワー行（B29 と同形のブロック追加とディープリンク）
3. **(C) 重複時確認（classes19 / classes21）**: 新規 `ConflictResolver`（トグル読取→存在チェック（MediaStore / SAF 両経路）→ Activity があればダイアログで「上書き／別名／キャンセル」をラッチ待ち、無ければ設定ポリシーで続行）を、両ダウンロードファネルの enqueue 直前に挿入し、結果ポリシーで当該アイテムのリクエストだけを作り替える。⚠ 設定項目の追加は B49 のリソース名規則に従うこと（本ビルドの初版はこれを守らず起動不能になった）

検証: JVM ハーネスで競合解決（各選択肢・Activity なし・例外時）とインデックス（重複排除・上限・破損耐性）を確認する。変更全クラスでレジスタ型ゲート PASS、最終 dex の正規化 diff が意図したファイルのみ

### B49. 設定リソース名の修正と resname_check ゲート（jp24.13.22.1、classes19 / resources.arsc）

[SPEC.md](SPEC.md) §47 の実装:

1. 設定レジストリへの登録で title / summary / icon に渡すのは**文字列リソース名**であり、表示文言そのものではない。B48 で追加した2項目の4文字列を正規名へ差し替え、対応する文字列リソースを arsc に追加する（既存エントリのバイトを変えない正規のテーブル成長手順で）
2. **常設ゲート**: `resname_check`（arsc からリソース名集合を構築し、登録引数・リソース取得 API・`getIdentifier` の全参照を実在照合。リソース名位置の非 ASCII リテラルは無条件 FAIL）を最終 APK に対して実行し、不合格ゼロを確認する。設定項目を触るビルドでは必須

### B50. 認証 API の実在化とルール一覧のデータ統一（jp24.13.22.2、classes21）

[SPEC.md](SPEC.md) §48 の実装:

1. **認証（SavedMediaFragment）**: プロンプト構築を実フレームワークの `BiometricPrompt.Builder` のみに変更する（API 30+ は許可認証子に STRONG｜DEVICE_CREDENTIAL、API 29 は端末資格情報許可フラグ。CancellationSignal は非 null）。⚠ **コンパイル用スタブでフレームワーククラスを覆い隠さないこと**。新規 API は実フレームワーク jar の `javap` でシグネチャを実在確認してから使う。エラーは握り潰さずログ＋専用文言で出す
2. **ルール一覧（PostFilterFragment）**: 行描画の前にアダプタへ `submit` し、ループ上限もアダプタの件数から取る（データを二重管理しない）

検証: テストダブルで旧形状のクラッシュ再現と新形状の一致を確認する。レジスタ型ゲート・resname_check PASS、CRC 差分が classes21.dex のみ

### B51. 比率ガードと列挙の段階化（jp24.13.23、classes21）

[SPEC.md](SPEC.md) §49 の実装:

1. **比率ガード（RecommendKeepGate）**: 収量判定に述語 `kept * 2 < total` を追加し、従来条件との OR で救済する。ページ下限の早期 return を「0件のみ」に変え、小さいページでもドライランが効くようにする。数え方（投稿収集・評価）は従来ガードと同一のものを再利用し、新しい数え方は作らない
2. **列挙の段階化（SavedMediaFragment）**: 照会を3段（選択拡大→無選択＋クライアント側フォルダ照合→Files 横断＋MIME 要求）に分け、追加0件なら次段へ進む。フォルダ照合は純粋関数として切り出し、JVM ハーネスで検証する。段階ごとの件数をログに出す

検証: ハーネスで比率の真理表と包含関係（従来条件は比率条件に含まれる）、境界（ちょうど半分は適用）を確認する。正規化 diff が対象2クラスのみ

### B52. 保存メディアの SAF 列挙（jp24.13.24、classes19 / classes21 / resources.arsc）

[SPEC.md](SPEC.md) §50 の実装:

1. **SAF 列挙（SavedMediaFragment / SavedMediaSaf）**: `DocumentsContract` の子カーソルでツリーを直接読む（DocumentFile は使わない）。再帰深さ4まで＋訪問済み docId で循環防止。ドットファイル・0バイト・未知／対象外 MIME を除外。ツリーごとに例外を隔離する。マージ順は SAF → MediaStore 段階列挙 → インデックス、重複排除は URI 一致＋ (表示名, サイズ) キーの先勝ち（MediaStore 射影にサイズを追加）
2. **ビューア専用許可（SavedMediaSettings / SavedMediaFolders）**: 設定キー4件（ツリー URI と表示パス、画像／動画）を新設し、認証成功後の画面からのみ既存のフォルダピッカーフローで取得する。ダウンロード先フォルダの許可は読み取り再利用する。設定登録ブロックと文字列リソース8件の追加は B49 の規則に従い、resname_check を必ず通す
3. **禁止事項の遵守**: ビューア経路でメディアスキャンを誘発しない、SAF ファイルの MediaStore 行を作らない、`.nomedia` に触れない。UX は 未許可／失効／正常 を出し分け、部分許可では一覧＋バナーを併せる

検証: JVM ハーネスで隠し名・MIME 受理・重複排除キー・先勝ちマージを確認する。resname_check 0 FAIL、CRC 差分が classes19.dex / classes21.dex / resources.arsc のみ

### B53. フォルダ許可の永続化を Activity 側へ（jp24.13.24.1、classes20 / classes21）

[SPEC.md](SPEC.md) §51 の実装:

1. **ピッカー側（classes20、FolderPicker / FolderPickerActivity）**: 起動 Intent に保存先の設定キー（ツリー URI 用・表示パス用）を載せる extras を追加し、結果受け取り時に Activity 内で `takePersistableUriPermission` ＋レジストリ書き込みを**先に**行ってから、静的コールバックを再読み込みの合図として発火する。extras の無い従来の呼び出し（ダウンロード先フォルダ用）は出荷経路のまま変えない。2引数の起動メソッドはバイト同一を保つ
2. **ビューア側（classes21）**: 起動時に保存先キーを渡す。許可経路の全地点（ピッカー戻り・保存成功・保存失敗）にログを入れ、無言の握り潰しを無くす。認証済みの場合は `onResume` で再読み込みする

検証: JVM ハーネス（Android 表面のフェイク＋インメモリレジストリ）で、コールバック喪失（プロセス再生成相当）でも Activity 側で保存されること、従来フローの無変更、キャンセル・書込み不可・古い Intent の各場合を確認する。CRC 差分が classes20.dex / classes21.dex のみ

### B54. 梱包アライメントの修正（jp24.13.24.2、梱包のみ）

[SPEC.md](SPEC.md) §52 の実装。内容変更なし:

1. 署名済み APK を素材に raw コピーで再構築する（圧縮データはバイト単位でコピー）。STORED エントリの extra は apksig 形式に統一する: `0xD935` ペイロード = uint16 のアライメント値（lib/ 配下は 4096、その他は 4）＋ゼロ詰め。パディング量は apksig の整列式に合わせる
2. **必須ゲート**: 最終署名 APK で (a) STORED .so 全件のデータ開始位置 % 4096 == 0、(b) 元 APK との全エントリ CRC 一致、(c) testzip クリーン、を実測する。再梱包を伴うビルドでは毎回行う

### B55. 設定保存 API の実在化と linkage_check ゲート（jp24.13.24.3、classes20 / classes21）

[SPEC.md](SPEC.md) §53 の実装:

1. **実 API の特定**: 出荷 dex を逆アセンブルし、設定クラスの実在メソッド表を確認する。書込みは基底クラスの `save(Object)`（final）のみ。動作確認済みの出荷コード（ダウンロード設定など）が使う呼び出し形に合わせる
2. **スタブの是正**: コンパイル用スタブを出荷形状に直す（基底の `save` は final・具体、サブクラスに存在しないオーバーロードを宣言しない）。Java ソース自体は変更しない。差分は invoke 3行のみになること
3. **常設ゲート `linkage_check`**: 拡張コードの全メソッド／フィールド参照を、APK 全体のクラス表（継承走査）＋フレームワーク表面と照合する検査を最終 APK に実行し、不合格ゼロを確認する。祖先クラス自体が不在の参照は警告に分類する。ハーネスのフェイクも出荷形状に合わせ、存在しない API を提供しない

検証: 旧ビルドに対して本ゲートが実機バグの2メソッドだけを検出すること、修正後が不合格ゼロであること。アライメントゲート（B54）も併せて PASS、CRC 差分が classes20.dex / classes21.dex のみ

## 手順 C — 再署名

1. 整列: STORED エントリのアライメントは署名で壊れることがある。梱包を組み直す場合は B54 の apksig 形式（`0xD935` にアライメント値）で書き、署名後に実測する。通常は `zipalign -p 4` で整列
2. `apksigner` で**自分の署名鍵**により署名する（スキームは v3 のみでよい。対象は API 29+）
3. 検証: `apksigner verify`、`aapt2 dump badging` でパッケージ名 `com.twitter.android.piko` / versionCode `312410001` / ラベル `Twitter` を確認

## 検証の作法（毎回行うこと）

- 施工前の dex は、無変更で再構築するとバイト一致すること（ラウンドトリップ再現性）を先に確認してから編集する。再現しない dex（ツールチェーン製法が違うもの）は全面再構築を避け、B11 のような最小バイナリパッチに切り替える
- **ブリッジ走査（jp24.5 で必須化）**: 拡張クラスの本体を差し替える前に、出荷バイナリで「実処理＋ `unpatchedBridge` の throw」を両方含むメソッドを洗い出す。該当クラスはメソッド単位のトランスプラントか smali 直接編集のみで触り、差し替え後にブリッジの実処理が残っていることを確認する。メソッド集合・署名・アクセスフラグの一致だけでは不十分
- **レジスタ型ゲート（jp24.9 で新設・jp24.13.1 で拡張、必須）**: 既存メソッドへ invoke を挿入したり引数レジスタに触れたりしたら、最終 dex に対してレジスタ型検査（smali の抽象解釈で、invoke 引数および iget / iput / aget / aput / array-length / instance-of / monitor / throw / check-cast のオブジェクトレジスタについて、合流点での型カテゴリ衝突を検出する）を実行し、違反ゼロを確認する。検査は名前が一致する**全メソッド**を対象にする（ブリッジ合成メソッドだけを掴んで「PASS」にしない）。jp24.8（挿入レジスタの型競合）と jp24.13（引数レジスタの使い捨て再定義）の2度の VerifyError 事故を受けた必須工程で、両事故の壊れたコードに対して ART と同一の指摘で FAIL することを確認済み
- 分岐や goto を含むメソッドを編集したら、レジスタ生存性スキャンを行い、合流点で未定義のレジスタが無いことを確認する（B17 の教訓）
- ファミリー差し替え後は、全クラス相互参照監査でベースライン比の新規欠落ゼロを確認する
- 各段階で、前段の APK との zip エントリ差分（CRC 比較）が「意図したエントリだけ」であることを assert する
- 最終 APK から編集対象 dex を再展開し、意図した変更だけが入っていることを再確認する
- **リソース名ゲート（jp24.13.22.1 で新設、必須）**: 設定項目の登録・追加を触ったら、最終 APK に対して `resname_check`（登録引数・リソース取得 API・`getIdentifier` の参照を arsc の実在名と照合。リソース名位置の非 ASCII リテラルは無条件 FAIL）を実行し、不合格ゼロを確認する。表示文言とリソース名を取り違えた jp24.13.22 の起動不能事故を受けた必須工程
- **連携ゲート（jp24.13.24.3 で新設、必須）**: 拡張コードが発行する全メソッド／フィールド参照について、参照先シグネチャが出荷 dex 群＋フレームワーク表面に実在することを照合する（`linkage_check`）。コンパイル用スタブやハーネスのフェイクは出荷形状に合わせ、存在しない API 表面を提供しない。jp24.13.22.2（フレームワーク API）と jp24.13.24.3（設定保存 API）の2度の「コンパイルが通るが実機に無いメソッド」事故を受けた必須工程で、旧ビルドに対して実機バグだけを正確に検出することを確認済み
- **アライメントゲート（jp24.13.24.2 で新設、再梱包時に必須）**: 最終署名 APK で STORED .so 全件のデータ開始位置が 4096 の倍数であることを実測する（B54）。署名は配置をずらし得るため、署名後の実物で測ること

### B56. フィルターバンドルのリクエストコード移行（jp24.13.24.4、classes21）
`FilterBundleImportExport` のファイル入出力リクエストコード定数を、取り込み 0x5042 → **0x5071**、書き出し 0x5052 → **0x5072** に変更する（上流 SettingsBackupRestore との衝突回避。コード空間の全数確認: BackupRestore 0x5042/0x5052、FeatureSwitch 0x5046/0x5056、UpdateFont 0x5061/0x5062、本バンドル 0x5071/0x5072）。src/FilterBundleImportExport.java は移行後の状態。

### B57. フィルターバンドルの「追加（マージ）」取り込み（jp24.13.24.5、classes21）
`FilterBundleImportExport` に検証→モード選択（上書き／追加／キャンセル）→適用の流れと完了サマリを追加し、各ストアにマージ用メソッドを追加する（`MuteCategoryStore.mergeFromImport`、`PostFilterRuleStore.mergeFromImport`、`BlockedAuthorsStore.addAll`、`RecommendKeepStore.mergeProjectionFromImport`）。意味論は SPEC §55。あわせて書き出しの `exportedBy` を "jp24.13.24.5" に更新。src/ の各ファイルは本手順適用後の状態。

### B58. ドロワー項目フィルターと ResourceUtils のキャッシュ化（jp24.13.24.6、classes20 / classes21）
- classes21: `app/morphe/extension/newx/misc/DrawerItemFilter` を src/DrawerItemFilter.java の内容に差し替える（非表示オプション → 解決済みタイトル集合の static キャッシュ。キー = 集合のコピー＋先頭ロケールの言語タグ。二重チェックロック。項目単位の失敗隔離は維持）。
- classes20: `app/morphe/extension/shared/ResourceUtils` は Java ソースが存在しないため smali を直接編集する。`getIdentifier(Context, ResourceType, String)`（getStringOrThrow の中央経路）に static ConcurrentHashMap キャッシュを追加する。キー = 型文字列 + ":" + 名前。成功結果のみ保存し、例外経路は従来どおり handleException を経て 0 を返しキャッシュに入れない。`<clinit>` でマップを初期化する static フィールドを追加する（他の static 初期値は従来どおり）。getSystemIdentifier（android パッケージ側）は対象外。

---

*本書は公開用の手順書です。数値・対象の正は [SPEC.md](SPEC.md)。版ごとの経緯は [CHANGELOG.md](CHANGELOG.md)。*
