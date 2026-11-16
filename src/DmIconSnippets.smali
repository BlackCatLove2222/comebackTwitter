# DmIconSnippets.smali — ナビバーの DM アイコン（jp24.6）の実物抜粋
#
# 下部ナビのタブ項目 composable は Lcom/x/android/main/s00;（classes19）の case 4。
# Chat 宛先のアイコンはアイコンレジストリ Lcom/x/icons/a;（classes18）の
#   選択時:   g1 = ic_vector_chat（塗りの吹き出し）
#   未選択時: s1 = ic_vector_chat_stroke（輪郭の吹き出し）
# 手順の全体は ../BUILD.md の B22 を参照。
#
# 断片1: 未選択時 — レジストリ初期化（classes18、Lcom/x/icons/a; の clinit）で
# s1 に入る drawable id を、アプリ内に残る封筒輪郭アイコンへ付け替える（1行）。
#
#   変更前: const v2, 0x7f0804c7   # ic_vector_chat_stroke
#   変更後: const v2, 0x7f080546   # ic_vector_email_stroke（封筒の輪郭、24x24）
#     invoke-direct {v0, v2}, Lcom/x/icons/b;-><init>(I)V
#     sput-object v0, Lcom/x/icons/a;->s1:Lcom/x/icons/b;
#
# 断片2: 選択時 — 塗りの封筒は在庫に無い（ic_vector_envelope_fill は 56x39 の
# イラストで不可）ため、res/drawable/ic_vector_chat.xml の pathData を自作の
# 塗り封筒に差し替える。自作ベクターの XML ソースは DmIconEnvelope.xml を参照。
# AXML の差し替えは文字列プールの再構築による外科編集（他チャンクはバイト不変）。
# 封筒の外形は ic_vector_email_stroke と同一（1.5–22.5 × 2.5–21.5、角丸）で、
# フラップの V 字は逆巻きサブパス（非ゼロ巻きで抜き）で表現する。
