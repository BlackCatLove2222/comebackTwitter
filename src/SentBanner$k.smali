.class public final Lpiko/jp135/SentBanner$k;
.super Lkotlin/coroutines/jvm/internal/ContinuationImpl;
.source "SourceFile"


# instance fields
.field public r:Lcom/x/inappnotification/api/n;

.field public s:Lcom/x/composer/upload/v2/notifications/n;

.field public t:Lcom/x/inappnotification/api/a;

.field public synthetic u:Ljava/lang/Object;

.field public w:I


# direct methods
.method public constructor <init>(Lcom/x/composer/upload/v2/notifications/n;Lcom/x/inappnotification/api/a;Lcom/x/inappnotification/api/n;Lkotlin/coroutines/jvm/internal/ContinuationImpl;)V
    .registers 5

    iput-object p1, p0, Lpiko/jp135/SentBanner$k;->s:Lcom/x/composer/upload/v2/notifications/n;

    iput-object p2, p0, Lpiko/jp135/SentBanner$k;->t:Lcom/x/inappnotification/api/a;

    iput-object p3, p0, Lpiko/jp135/SentBanner$k;->r:Lcom/x/inappnotification/api/n;

    invoke-direct {p0, p4}, Lkotlin/coroutines/jvm/internal/ContinuationImpl;-><init>(Lkotlin/coroutines/Continuation;)V

    return-void
.end method


# virtual methods
.method public final invokeSuspend(Ljava/lang/Object;)Ljava/lang/Object;
    .registers 6

    iput-object p1, p0, Lpiko/jp135/SentBanner$k;->u:Ljava/lang/Object;

    iget v0, p0, Lpiko/jp135/SentBanner$k;->w:I

    const/high16 v1, -0x80000000

    or-int/2addr v0, v1

    iput v0, p0, Lpiko/jp135/SentBanner$k;->w:I

    iget-object v0, p0, Lpiko/jp135/SentBanner$k;->s:Lcom/x/composer/upload/v2/notifications/n;

    iget-object v1, p0, Lpiko/jp135/SentBanner$k;->t:Lcom/x/inappnotification/api/a;

    iget-object v2, p0, Lpiko/jp135/SentBanner$k;->r:Lcom/x/inappnotification/api/n;

    invoke-static {v0, v1, v2, p0}, Lpiko/jp135/SentBanner;->show(Lcom/x/composer/upload/v2/notifications/n;Lcom/x/inappnotification/api/a;Lcom/x/inappnotification/api/n;Lkotlin/coroutines/jvm/internal/ContinuationImpl;)Ljava/lang/Object;

    move-result-object p0

    return-object p0
.end method
