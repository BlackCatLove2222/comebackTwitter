# comebackTwitter

**X（旧 Twitter）の現行アプリを、2020 年頃の Twitter の感覚に寄せて日本語で使うための、Piko NewX 向け改造セットです。**

ベースは X 12.41.0-prod.01 ＋ crimera/piko-newx v3.75.0。アプリのラベルは「Twitter」に戻り、青い鳥がヘッダーに帰ってきます。UI の日本語化、2020 年基準の初期設定、要らない要素の除去、更新音と「引っ張って更新」の質感まで、当時の使い心地を可能な範囲で再現します。

> 本リポジトリが配布するのは、私たちが作成したパッチ・翻訳・ソースコード・ドキュメントだけです。**パッチ済み APK や X アプリ本体は一切配布しません。** 本家 APK は各自で用意し、Morphe で自分のコピーにパッチを当ててください。

## 特徴（要約 — 詳細は [SPEC.md](SPEC.md)）

- **完全日本語化**: Piko NewX の設定 UI（リソース文字列 486 件すべて）と、拡張内にハードコードされた表示文字列 135 種を翻訳
- **Twitter 用語への復帰**: ポスト→ツイート、リポスト→リツイート、引用ポスト→引用ツイート
- **2020 年基準の初期設定**: ドロワーの整理（リストは表示のまま）、ダークスタイル Dim、ナビの Grok 非表示、表示回数の非表示、共有リンクのドメインは twitter.com
- **スレッド表示の復旧**: 返信の並び順を**関連性に恒久ピン留め**し、著者の自己スレッドが連結して先に並ぶようにした（jp22。シートで一時変更しても詳細を開き直せば関連性に戻る）
- **詳細ヘッダ行の整理**: 行の左側のソートタブを除去（jp23、上級手順）。右のピボットは、他者の投稿で引用があるとき **「N件の引用を表示」** になる（引用数はサーバー応答に含まれているが純正マッパが読み捨てていたものを搬送。タップ先は従来どおり引用一覧）。この行は返信数にかかわらず常時表示（jp24.4。純正は返信2件以上でしか出なかった）
- **ゾンビフィルター**（jp24、jp24.8 / jp24.10 で拡張）: スレッド内のゾンビ返信（外国語文字種、絵文字のみ、重複・近似重複、本文コピー＋リンク/引用、連投、引用テンプレ使い回し、短文＋引用ベイト、日本語スレッドへの外国語ベイト、同じ投稿への2回目のリプライ）を理由コード付きで非表示。投稿フィルター画面でルール別にオン/オフでき、隠した返信は「フィルタされた返信」から理由つきで見直し・ホワイトリスト追加が可能
- **指定アカウント非表示**（jp24.11 / jp24.12）: 指定したアカウントの投稿を、スレッド・タイムライン・プロフィールでクライアント側だけで非表示（サーバーには触れず、ブロックはしません）。数値 ID で照合し、引用・リポスト経由も対象。開いたスレッドの先頭だけは消えません。同梱の対象リストは空なので、自分のビルドで対象 ID を入れて使います
- **カテゴリ別ミュート**（jp24.13）: ニュース / 政治系 / 荒らし / 晒し系 / 炎上系 のカテゴリ単位で投稿を非表示。本文と引用文を照合し、隠した投稿は「どのカテゴリの・どの語で」まで理由に記録。晒し系には電話番号形式などの個人情報パターン検出つき。語彙の編集とカスタムカテゴリの追加も可能
- **上部バーの常時表示**（jp24.11 で統合）: ホームの上部バー（タブ帯）がスクロールで隠れないように固定
- **通知許可プロンプトの抑止**（jp24.13.2）: アプリ更新後などに出る通知許可のボトムシート（「大切なモーメントを見逃さない」）を表示しないようにした
- **起動時の OS 許可ダイアログも抑止**（jp24.13.4）: 上記のスイッチ固定の受け皿側にいた、起動時の直接リクエストも停止。アプリが能動的に許可を求めることはなくなり、通知を有効化したい場合は端末設定から手動で切り替える
- **送信完了バナーのクラシック化**（jp24.13.5）: 「ツイートを送信しました」を、現行のダークなカード＋全幅ボタンから、往年の水色バナー（青丸に白チェック、ボタンなし、上部に浮動）に差し替え。バナーをタップすると送信したツイートが開く。予約投稿の確認とエラー通知は従来表示のまま
- **コンポーザーのブースト勧誘を抑止**（jp24.13.6）: 投稿作成中に入力が数秒止まると出るブースト（有料プロモート）のアップセルを表示しない。自分の投稿に出る常駐の「ブースト」ボタン自体は残る
- **設定への直接入口**（jp24.13.3）: ドロワーに「ツイートフィルター」行を追加し、投稿フィルター画面（ゾンビ／カテゴリ／キーワードの設定）へ直接届くようにした。同画面のスクロール不全（カテゴリ設定に届かない問題）も併せて修正
- **ダウンロードの保存先デフォルト**（jp24.3）: 保存先フォルダ未設定でも、画像は Pictures/Twitter、動画は Movies/Twitter に保存される（フォルダ設定済みなら従来どおりそちら優先）。データ使用量の初期値も初回だけ調整（動画自動再生オフ、画質は Wi-Fi/モバイル、jp24.2）
- **DM アイコンの復活**（jp24.6）: 下部ナビ右端のチャットアイコンを、従来の DM マーク（封筒）に置き換え。未選択時は封筒の輪郭、選択時は塗りの封筒
- **プロフィールタブは4つだけ**: ツイート / 返信 / メディア / いいね
- **除去した要素**: ホームの「追加」タイル、プロフィールヘッダーの Grok・検索アイコン、自分プロフィールの「共有」ボタン、「Made on X（{logo}で作成）」ラベル、「Grokで作成」ラベル
  - ※ 写真のユーザータグ付けは**残しています**（同じ位置に出ますが別物です）
- **テーマと外観**: ダークは2色 — ダークブルー（Dim、背景 #15202B）と、完全な黒（#000）の「ブラック」（本体の Lights Out は #030303 止まりで純黒ではないため自前で追加）。Piko設定の「外観」セレクタで **ライト / ダークブルー / ブラック / 端末の設定を使う** を1箇所で選べます（ドロワーの月アイコンのシートとも共存し、そちらの操作は上書きされません）
- **「返信先」行の復活**: 返信投稿に「返信先: @〜さん」行が戻ります。ただし表示はサーバーが返信リンクデータを返す画面だけ（通知タブでは表示を確認済み。ツイート詳細のスレッドでは現行のサーバー応答にデータが無いため表示されません — [SPEC.md](SPEC.md) §15）
- **更新音の復活**: 引っ張って更新の開始時に「プスッ」、完了時に「ポン」（Twitter 8.65 の実物音源を各自で抽出して埋め込む方式 — 音源自体は配布しません）
- **「引っ張って更新」の再現**: 白バッジ＋円弧が深い位置に出現し、飽和曲線で沈み、離すと少し上がって固定位置で Material 風に伸縮回転。リング寸法と矢じりは旧 Twitter アプリの実物（MaterialProgressDrawable）準拠。ヘッダーの青鳥は stock サイズの静止画
- **できなかったこと（正直な記録）**: 投稿元ラベル（「Twitter for Android」等）は復活できません。現行 X の GraphQL は `source` フィールド自体を要求しておらず、クライアントにデータが届きません。他人のいいね一覧もサーバー側の仕様で非公開です

## 必要なもの

| もの | 備考 |
|---|---|
| X 12.41.0-prod.01 の APK（APKM 形式） | **各自で用意してください**（本リポジトリには含まれません）。バージョンが違うとパッチが当たりません |
| Morphe パッチャー | Morphe Manager、または Morphe CLI（morphe-desktop）。https://morphe.software |
| （任意）JDK / Android build-tools | 後処理（[BUILD.md](BUILD.md) の手順 B）を行う場合に必要です |

## 適用手順の概略

1. Morphe で `patches/patches-3.75.0-jp.mpp` をパッチソースとして読み込み、`patches/options-jp.json` の設定（Clone 有効＝パッケージ名 `com.twitter.android.piko`、Restore Twitter branding 有効）で、自分の X 12.41.0-prod.01 にパッチを当てる（48 パッチ適用）
2. 完全な jp24.13.6 仕様にするには、[BUILD.md](BUILD.md) の「手順 B: 後処理」も実施する（初期設定の焼き込み、フィーチャースイッチ上書き登録、更新音、PTR オーバーレイ、ラベル消去の追加パッチ、純黒テーマ、返信先行、関連性ピン留め、引用数ピボット、ゾンビフィルターとその拡張、データ使用量デフォルト、保存先デフォルト、引用行の常時表示、DM アイコン、上部バー常時表示、指定アカウント非表示、カテゴリ別ミュート、通知プロンプト抑止、設定への到達性修正、起動時 OS ダイアログ抑止、送信完了バナーのクラシック化、ブースト・アップセル抑止等）。なお「外観」セレクタ（B14）と関連性ピン留めの一部（B15）は拡張ソースの変更としてバンドルに表現できる。ソートタブ除去（B17）は任意の上級手順で、省略した場合は jp22 形（タブは見えるがピン留めで実質無力）になる
3. 自分の署名鍵で署名してインストールする（**署名鍵は全バージョンで同じものを使い続けてください**。鍵が違うと上書き更新できません）

詳しい手順と各変更の施工箇所は [BUILD.md](BUILD.md)、維持すべき仕様の一覧は [SPEC.md](SPEC.md)、版ごとの変更履歴は [CHANGELOG.md](CHANGELOG.md) を参照してください。

## 注意

- 本プロジェクトは非公式のファンメイド改造であり、**X Corp. とは一切関係ありません**。「Twitter」「X」等の名称・ロゴは各権利者の商標です
- 本リポジトリはパッチ済み APK・アプリ本体・音源ファイル・署名鍵を配布しません。パッチの適用は各自の責任で行ってください
- X 本体のアップデートやサーバー側の仕様変更により、予告なく動作しなくなることがあります
- アカウントへの影響等について作者は責任を負いません。心配な場合はサブアカウント等で試してください

## クレジット

- 作者: **ネコセンパイ**（[BlackCatLove2222](https://github.com/BlackCatLove2222)）
- 共作: **ミスティ**
- 上流プロジェクト: [crimera/piko-newx](https://github.com/crimera/piko-newx)（パッチ本体。ライセンス条件は上流リポジトリを確認してください）、[Morphe](https://morphe.software)（パッチャー基盤。ReVanced の系譜）

アプリ内の Piko バージョン表示には「Piko %s · ミスティ」のクレジットが入ります。

---

## English summary

**comebackTwitter** is a Japanese customization set for [Piko NewX](https://github.com/crimera/piko-newx) (X 12.41.0-prod.01) that brings the client as close as possible to the circa-2020 Twitter experience: full Japanese localization of the patch UI, Twitter-era terminology (ツイート/リツイート), 2020-baseline default settings, removal of modern clutter (the home "add tab" tile, header Grok/search icons, the own-profile Share button, the "Made on X" and "Created with Grok" labels — photo user-tags are kept), the classic pull-to-refresh sounds, and a faithful recreation of the classic Material pull-to-refresh spinner (white badge, saturating descent, fixed hold position, ~1.3 s stretch-spin cycle, pull-phase arrowhead). It also adds a true pure-black (#000) dark theme alongside Dim (#15202B), a unified Appearance selector (Light / Dark Blue / Black / Follow system) in the patch settings that coexists with the native theme sheet, and restores the "Replying to @…" row on reply posts wherever the server supplies the reply link data (verified in the Notifications tab; the thread view's server response does not include that data, so the row cannot appear there). It also pins the thread reply sorting to Relevance permanently, so authors' self-threads render chained again (jp22), removes the sort tab from the tweet-detail header row, and turns the header pivot into a quote-count pivot that reads "N quotes" (e.g. "8件の引用を表示") on other people's posts with quotes — the count is present in the server response but was dropped by the stock client mapper, and is now carried through (jp23); the header row is always shown regardless of reply count (jp24.4). It adds a "zombie filter" that hides spammy thread replies (foreign-script, emoji-only, duplicate/near-duplicate, copied-text-with-link/quote, repeat-scatter, recycled quote templates, short quote-bait, non-Japanese bait replies in Japanese threads, and repeat direct replies to the same post) with per-rule toggles and a review list that records the reason for every hidden reply (jp24, extended in jp24.8/jp24.10), sensible data-usage defaults on first run (jp24.2), MediaStore download defaults — Pictures/Twitter and Movies/Twitter — when no download folder is configured (jp24.3), and restores the classic envelope DM icon in the bottom navigation bar (jp24.6). Later builds add client-side-only hiding of designated accounts by numeric author ID across threads, timelines and profiles (jp24.11/jp24.12; the bundled seed list is empty — builders supply their own IDs), category-based muting with five preset categories (news / politics / trolling / doxxing / flame-bait) that records which word matched (jp24.13), an always-visible top app bar, suppression of the post-update notification-permission prompt sheet (jp24.13.2), and a drawer shortcut that opens the post-filter settings directly (jp24.13.3). Further builds also stop the startup OS permission request that the sheet fix wakes up (jp24.13.4; notifications can still be enabled manually in Android settings), replace the post-sent confirmation with the classic light-blue banner — blue check circle, no button, tap opens the sent post, with a native fallback when context is unavailable (jp24.13.5; scheduled-post confirmations and error notifications keep the native UI), and suppress the composer's boost upsell that appears after a typing pause (jp24.13.6; the resident Boost button on your own posts remains).

This repository distributes **only our own work** — the modified patch bundle, translations, helper source code, and documentation. It does **not** contain any patched APK, any part of the X app, sound files, or signing keys. Obtain your own copy of X 12.41.0-prod.01 and patch it yourself with [Morphe](https://morphe.software). See [BUILD.md](BUILD.md) for the full procedure and [SPEC.md](SPEC.md) for the maintained specification. Unofficial fan work; not affiliated with X Corp. Author: ネコセンパイ (BlackCatLove2222), co-author: ミスティ. Based on crimera/piko-newx; refer to the upstream repository for its terms.
