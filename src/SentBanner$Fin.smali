.class public final Lpiko/jp135/SentBanner$Fin;
.super Ljava/lang/Object;
.implements Lpiko/jp135/BannerUi$Done;
.source "SourceFile"


# static fields
.field public static final a:Lpiko/jp135/SentBanner$Fin;


# direct methods
.method static constructor <clinit>()V
    .registers 1

    new-instance v0, Lpiko/jp135/SentBanner$Fin;

    invoke-direct {v0}, Lpiko/jp135/SentBanner$Fin;-><init>()V

    sput-object v0, Lpiko/jp135/SentBanner$Fin;->a:Lpiko/jp135/SentBanner$Fin;

    return-void
.end method

.method public constructor <init>()V
    .registers 1

    invoke-direct {p0}, Ljava/lang/Object;-><init>()V

    return-void
.end method


# virtual methods
.method public done(I)V
    .registers 2

    invoke-static {p1}, Lpiko/jp135/SentBanner;->finish(I)V

    return-void
.end method
