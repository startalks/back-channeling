# Back channeling

![Back Channeling](./resources/public/img/logo.png)

よくあるChatにありがちなチャネルによる分類ではなく、板>スレッド型の掲示板をリアルタイム化したようなものです。
あとJVM上で動くいい感じのWebチャットがないので、車輪の再発明したい。

## Setup

Currently, we support a development mode only.

Start datomic.

```
% bin/transactor config/xxx.properties
```

```
% lein with-profile production run
```

A default port is 3009.

## Features

Back channeling is a realtime bbs.

### Create a thread

### Comment to a thread

#### Thread
