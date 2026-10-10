# 開発用のイメージ (dev). 最後の段 (emulator) は, エミュレータを動かすときだけ使う
FROM ubuntu:24.04 AS dev

ENV DEBIAN_FRONTEND=noninteractive

# ログの日本語 (Kotlin のメッセージ, KDoc の警告など) を, 文字化けさせない
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8

# JDK 17 (Android Gradle Plugin は 17 以上が必要. ホストの JDK は使わない)
RUN apt-get update && apt-get install -y --no-install-recommends \
    openjdk-17-jdk-headless \
    unzip \
    curl \
    ca-certificates \
    git \
    && rm -rf /var/lib/apt/lists/*

# Gradle (ラッパー gradlew を作り直すときだけ使う. ビルドは ./gradlew で行う)
ARG GRADLE_VERSION=9.8.1
RUN curl -fL -o /tmp/gradle.zip https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip && \
    unzip -q /tmp/gradle.zip -d /opt && \
    ln -s /opt/gradle-${GRADLE_VERSION}/bin/gradle /usr/local/bin/gradle && \
    rm /tmp/gradle.zip

# Android SDK (コマンドラインツール)
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ARG CMDLINE_TOOLS=16111833
RUN mkdir -p ${ANDROID_HOME}/cmdline-tools && \
    curl -fL -o /tmp/cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS}_latest.zip && \
    unzip -q /tmp/cmdtools.zip -d ${ANDROID_HOME}/cmdline-tools && \
    mv ${ANDROID_HOME}/cmdline-tools/cmdline-tools ${ANDROID_HOME}/cmdline-tools/latest && \
    rm /tmp/cmdtools.zip
ENV PATH=${ANDROID_HOME}/cmdline-tools/latest/bin:${ANDROID_HOME}/platform-tools:${PATH}

# SDK のコンポーネント. コンテナは一般ユーザーで動き, SDK は読み取り専用になる.
# 足りないコンポーネントは, Gradle が, 実行時に, インストールできない. 必要になったら, ここに足す.
# compileSdk 37 は, AWS SDK for Kotlin が使う OkHttp 5.5 の要求. build-tools は, app/build.gradle.kts で固定する.
RUN yes | sdkmanager --licenses > /dev/null && \
    sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;37.0.0" && \
    chmod -R a+rX ${ANDROID_HOME}

# Gradle のキャッシュ. 名前付きボリュームを mount する (空のボリュームは, この権限を引き継ぐ)
ENV GRADLE_USER_HOME=/gradle-home
RUN mkdir -p /gradle-home && chmod 777 /gradle-home

# コンテナはホストユーザーで実行されるが, イメージ内の /workspace は, そのユーザーの所有ではないため
RUN git config --system --add safe.directory '*'

WORKDIR /workspace

# エミュレータ用のイメージ (emulator). dev に, Android Emulator と, システムイメージと, 画面 (Xvfb と VNC) を足す.
# 大きい (システムイメージが約 1.9 GB) ので, 別の段にして, ビルドと, テストの dev には, 入れない.
FROM dev AS emulator

# 画面: Xvfb (仮想の画面), x11vnc, noVNC (ブラウザで見る). ライブラリ: エミュレータの Qt と, GL (ソフトウェア描画) に必要
RUN apt-get update && apt-get install -y --no-install-recommends \
    xvfb \
    x11vnc \
    novnc \
    websockify \
    libgl1 \
    libpulse0 \
    libnss3 \
    libxkbcommon-x11-0 \
    libxcb-cursor0 \
    libxcb-icccm4 \
    libxcb-image0 \
    libxcb-keysyms1 \
    libxcb-render-util0 \
    libxcb-shape0 \
    libxcb-xinerama0 \
    libxcb-xkb1 \
    libxcomposite1 \
    libxcursor1 \
    libxi6 \
    libxrandr2 \
    libxtst6 \
    libasound2t64 \
    procps \
    && rm -rf /var/lib/apt/lists/*

# エミュレータ本体と, システムイメージ (API 36. Google API. x86_64). KVM で動かす
ARG EMULATOR_IMAGE="system-images;android-36;google_apis;x86_64"
RUN sdkmanager "emulator" "${EMULATOR_IMAGE}" && chmod -R a+rX ${ANDROID_HOME}
ENV PATH=${ANDROID_HOME}/emulator:${PATH}

# AVD (仮想の端末) と, adb の鍵の置き場. 名前付きボリュームを mount する (空のボリュームは, この権限を引き継ぐ)
ENV ANDROID_USER_HOME=/avd
ENV ANDROID_EMULATOR_HOME=/avd
ENV ANDROID_AVD_HOME=/avd/avd
ENV EMULATOR_IMAGE=${EMULATOR_IMAGE}
RUN mkdir -p /avd && chmod 777 /avd

# 仮想の画面の番号
ENV DISPLAY=:99
