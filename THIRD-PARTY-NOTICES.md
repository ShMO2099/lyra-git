# Third-Party Notices

Lyra is distributed with the third-party components below, each under its own licence. This file
lists them, reproduces the copyright notices and licence texts those licences require, and
says where to get the source of each component whose licence asks for that.

Other notices that ship with the application. A packaged build carries the first four in
its `app/legal` directory (`lib/app/legal` on Linux), beside this file:

- [`LICENSE`](LICENSE) is Lyra's own licence, Apache-2.0, and the Apache-2.0 text the bundled
  Apache-licensed components require a copy of.
- [`NOTICE`](NOTICE) reproduces the NOTICE files of the bundled Apache-licensed components.
- [`third-party/tm4e/EPL-2.0.txt`](third-party/tm4e/EPL-2.0.txt) is the Eclipse Public License
  2.0, which covers Eclipse TM4E core.
- [`src/main/resources/grammars/NOTICE.md`](src/main/resources/grammars/NOTICE.md) and
  [`src/main/resources/grammars/tm/NOTICE.txt`](src/main/resources/grammars/tm/NOTICE.txt)
  cover the bundled TextMate grammars. Both are inside the application jar, under `grammars/`.
- [`src/main/resources/native/LICENSE-libgit2.txt`](src/main/resources/native/LICENSE-libgit2.txt)
  and `AUTHORS-libgit2.txt` cover libgit2 and the code it bundles (zlib, PCRE2, xdiff and
  others). Both are inside the application jar, under `native/`, and in `app/legal`.
- A packaged build's Java runtime is made by `jlink` from the JDK, which writes the JDK's own
  licence and notices into the runtime's `legal` directory.

**Keeping this file current.** The packaging scripts ship exactly the jars that
`mvn dependency:list -DincludeScope=runtime` prints. When a dependency is added, removed or
upgraded, update the table below and, for a permissive licence, the copyright lines in its
section.

## Components

| Component | Version | Licence | Source |
|---|---|---|---|
| Java runtime: Eclipse Temurin JDK, the modules `jlink` keeps (packaged builds only) | the build's JDK 25; the runtime's `release` file and `bin/java -version` name it | GPL-2.0 with the Classpath Exception | <https://github.com/adoptium/jdk25u> |
| OpenJFX (`javafx-base`, `javafx-graphics`, `javafx-controls`) | 27 | GPL-2.0 with the Classpath Exception | <https://github.com/openjdk/jfx> |
| Eclipse JGit (`org.eclipse.jgit`, `.ssh.apache`, `.ssh.apache.agent`) | 7.2.1.202505142326-r | Eclipse Distribution License 1.0 (BSD-3-Clause) | <https://github.com/eclipse-jgit/jgit> |
| Eclipse TM4E core (`org.eclipse.tm4e.core`), modified: one class changed | 0.18.0-20260827.121906-2-jfxgit1, from upstream build 0.18.0-20260827.121906-2 | EPL-2.0 | <https://github.com/eclipse-tm4e/tm4e> |
| libgit2 (native library) | 1.9.7 | GPL-2.0 with a linking exception | <https://github.com/libgit2/libgit2> |
| Apache MINA SSHD (`sshd-osgi`, `sshd-sftp`) | 2.15.0 | Apache-2.0 | <https://github.com/apache/mina-sshd> |
| Apache Commons Codec | 1.18.0 | Apache-2.0 | <https://commons.apache.org/proper/commons-codec/> |
| Gson | 2.13.2 | Apache-2.0 | <https://github.com/google/gson> |
| Error Prone annotations | 2.41.0 | Apache-2.0 | <https://github.com/google/error-prone> |
| JavaEWAH | 1.2.3 | Apache-2.0 | <https://github.com/lemire/javaewah> |
| JNA, JNA Platform | 5.16.0 | Apache-2.0 (offered as Apache-2.0 or LGPL-2.1-or-later; used under Apache-2.0); its native libraries bundle libffi under MIT | <https://github.com/java-native-access/jna> |
| SnakeYAML Engine | 2.10 | Apache-2.0 | <https://bitbucket.org/snakeyaml/snakeyaml-engine> |
| JCL over SLF4J | 1.7.36 | Apache-2.0 | <https://github.com/qos-ch/slf4j> |
| SLF4J API | 1.7.36 | MIT | <https://github.com/qos-ch/slf4j> |
| AtlantaFX | 3.0.0 | MIT | <https://github.com/mkpaz/atlantafx> |
| joni | 2.2.1 | MIT | <https://github.com/jruby/joni> |
| jcodings | 1.0.58 | MIT | <https://github.com/jruby/jcodings> |
| autolink-java | 0.11.0 | MIT | <https://github.com/robinst/autolink-java> |
| RichTextFX | 0.11.7 | BSD-2-Clause (offered as BSD-2-Clause or GPL-2.0 with the Classpath Exception; used under BSD-2-Clause) | <https://github.com/FXMisc/RichTextFX> |
| ReactFX | 2.0-M5 | BSD-2-Clause | <https://github.com/TomasMikula/ReactFX> |
| Flowless | 0.7.4 | BSD-2-Clause | <https://github.com/FXMisc/Flowless> |
| UndoFX | 2.1.1 | BSD-2-Clause | <https://github.com/FXMisc/UndoFX> |
| WellBehavedFX | 0.3.3 | BSD-2-Clause | <https://github.com/FXMisc/WellBehavedFX> |
| commonmark-java (with the `autolink`, `gfm-strikethrough` and `gfm-tables` extensions) | 0.24.0 | BSD-2-Clause | <https://github.com/commonmark/commonmark-java> |
| TextMate grammars | see their notices | MIT, Apache-2.0, Apache-2.0 with the LLVM Exception, BSD-2-Clause, BSD-3-Clause, ISC, MPL-2.0, the TextMate bundle licence | see [`grammars/NOTICE.md`](src/main/resources/grammars/NOTICE.md) |

All of these are bundled unmodified, except Eclipse TM4E core (below) and the grammar
files whose repairs `grammars/NOTICE.md` and `grammars/tm/NOTICE.txt` record.

## Source code for the copyleft components

Every GitHub release of Lyra carries, as assets beside the packages, the complete source of
each GPL-licensed component those packages contain: `source-libgit2-<version>.tar.gz`,
`source-openjfx-<version>.tar.gz` and `source-temurin-jdk-<version>.tar.gz`, the last for the
exact JDK build the runtime was linked from. They are offered from the same place as the
packages, as GPL-2.0 section 3 asks. The upstream locations are below.

- **Java runtime** (GPL-2.0 with the Classpath Exception): Eclipse Temurin, built from
  <https://github.com/adoptium/jdk25u> at the tag of its version (`jdk-25.0.5+8`, for
  example). Adoptium publishes each build's source as `OpenJDK25U-jdk-sources_*.tar.gz`
  with its binaries, <https://github.com/adoptium/temurin25-binaries/releases>.
- **OpenJFX 27** (GPL-2.0 with the Classpath Exception): <https://github.com/openjdk/jfx/tree/27-ga>.
  The jars are the ones published to Maven Central as `org.openjfx:javafx-*:27`.
- **Eclipse TM4E core** (EPL-2.0), a modified build. Upstream's source is
  <https://github.com/eclipse-tm4e/tm4e> at commit `45d6d5c5b35bd29be7c8948d0e2e8d3af5f6f80c`,
  the source of snapshot build `0.18.0-20260827.121906-2`. One class is changed,
  `org.eclipse.tm4e.core.internal.oniguruma.OnigRegExp`, so that each distinct pattern is
  compiled once and shared. Eclipse's signature is removed, because the changed class no
  longer matches it. The changed class's full source, available under EPL-2.0, is in this
  project's source tree under [`third-party/tm4e/src`](third-party/tm4e/src), with the
  script that builds the jar and a README describing the change.
- **libgit2 1.9.7** (GPL-2.0 with a linking exception):
  <https://github.com/libgit2/libgit2/tree/v1.9.7>, commit
  `49e408b3208bc3093757a1c2db938d3590f3f412`. The bundled binaries are built from that source,
  unmodified; [`src/main/resources/native/README.md`](src/main/resources/native/README.md)
  records how.
- **MPL-2.0 grammar files**: bundled verbatim; each file's upstream is listed in
  [`grammars/tm/NOTICE.txt`](src/main/resources/grammars/tm/NOTICE.txt).

JNA and RichTextFX are each offered under a copyleft licence as well as a permissive one.
Lyra uses them under the permissive one (Apache-2.0 and BSD-2-Clause respectively).

## Licence texts and copyright notices

### Apache License 2.0

The full text is in [`LICENSE`](LICENSE). It covers Apache MINA SSHD, Apache Commons Codec,
Gson, Error Prone annotations, JavaEWAH, JNA, SnakeYAML Engine and JCL over SLF4J. Their
NOTICE files are reproduced in [`NOTICE`](NOTICE).

### MIT License

Applies to the components below, under these copyright notices:

- AtlantaFX: Copyright (c) 2022 mkpaz
- joni: Copyright (c) 2017 JRuby Team
- jcodings: the JRuby Team (the licence file at 1.0.58 names no copyright holder)
- autolink-java: Copyright (c) 2015 Robin Stocker
- SLF4J API: Copyright (c) 2004-2022 QOS.ch Sarl (Switzerland)
- libffi, inside JNA's native libraries: Copyright (c) 1996-2022 Anthony Green, Red Hat, Inc and others
- SHA-1 UbcCheck, inside Eclipse JGit: Copyright (c) 2017 Marc Stevens, Cryptology Group,
  Centrum Wiskunde & Informatica; Dan Shumow, Microsoft Research

```
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

### BSD 2-Clause License

Applies to the components below, under these copyright notices:

- RichTextFX: Copyright (c) 2013-2023, Tomas Mikula and contributors
- ReactFX: Copyright (c) 2013-2014, Tomas Mikula
- Flowless: Copyright (c) 2014, TomasMikula
- UndoFX: Copyright (c) 2014, TomasMikula
- WellBehavedFX: Copyright (c) 2014, TomasMikula
- commonmark-java: Copyright (c) 2015, Atlassian Pty Ltd

```
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

* Redistributions in binary form must reproduce the above copyright notice,
  this list of conditions and the following disclaimer in the documentation
  and/or other materials provided with the distribution.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

### Eclipse Distribution License 1.0: Eclipse JGit

```
Eclipse Distribution License - v 1.0

Copyright (c) 2007, Eclipse Foundation, Inc. and its licensors.

All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.

Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.

Neither the name of the Eclipse Foundation, Inc. nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
POSSIBILITY OF SUCH DAMAGE.
```

### Eclipse Public License 2.0: Eclipse TM4E core

The full text is in [`third-party/tm4e/EPL-2.0.txt`](third-party/tm4e/EPL-2.0.txt), and in
a packaged build's `app/legal/EPL-2.0.txt`; it is also at <https://www.eclipse.org/legal/epl-2.0/>.
The source, including the changed class, is available under EPL-2.0 as set out above.

### GPL-2.0 with the Classpath Exception: the Java runtime and OpenJFX

A packaged build's Java runtime carries the full text in `runtime/legal/java.base/LICENSE`
(`lib/runtime/legal/java.base/LICENSE` on Linux); it is also at
<https://openjdk.org/legal/gplv2+ce.html>. The source is available as set out above.

### GPL-2.0 with a linking exception: libgit2

The full text, with the licences of the code libgit2 bundles, is in
[`src/main/resources/native/LICENSE-libgit2.txt`](src/main/resources/native/LICENSE-libgit2.txt).

## Third-party code inside OpenJFX's native libraries

OpenJFX lists these components for `javafx.graphics`. Its published jars do not include its
`legal` directory, so the notices are reproduced here from
<https://github.com/openjdk/jfx/tree/27-ga/modules/javafx.graphics/src/main/legal>.

**This software is based in part on the work of the Independent JPEG Group.**

- **IJG JPEG**, in the image decoder on every platform: notice below.
- **Mesa 3-D Graphics Library** and **PipeWire** headers, in the Linux native libraries:
  notices below.
- **GCC's libgcc and libstdc++**, in the Linux native libraries: GPL-3.0 with the GCC Runtime
  Library Exception, <https://www.gnu.org/licenses/gcc-exception-3.1.html>.
- **The Microsoft Visual C++ and Universal C runtime libraries** (`vcruntime140.dll`,
  `msvcp140*.dll`, `ucrtbase.dll`, `api-ms-win-*.dll`), in the Windows jar: redistributed by
  OpenJFX under Microsoft's terms for those redistributable files.

### Independent JPEG Group (IJG) JPEG version 10

#### IJG License
```
Copyright (C) 1991-1998, Thomas G. Lane.
Copyright (C) 1991-2026, Thomas G. Lane, Guido Vollbeding.

The authors make NO WARRANTY or representation, either express or implied,
with respect to this software, its quality, accuracy, merchantability, or
fitness for a particular purpose.  This software is provided "AS IS", and you,
its user, assume the entire risk as to its quality and accuracy.

This software is copyright (C) 1991-2026, Thomas G. Lane, Guido Vollbeding.
All Rights Reserved except as specified below.

Permission is hereby granted to use, copy, modify, and distribute this
software (or portions thereof) for any purpose, without fee, subject to these
conditions:
(1) If any part of the source code for this software is distributed, then this
README file must be included, with this copyright and no-warranty notice
unaltered; and any additions, deletions, or changes to the original files
must be clearly indicated in accompanying documentation.
(2) If only executable code is distributed, then the accompanying
documentation must state that "this software is based in part on the work of
the Independent JPEG Group".
(3) Permission for use of this software is granted only if the user accepts
full responsibility for any undesirable consequences; the authors accept
NO LIABILITY for damages of any kind.

These conditions apply to any software derived from or based on the IJG code,
not just to the unmodified library.  If you use our work, you ought to
acknowledge us.

Permission is NOT granted for the use of any IJG author's name or company name
in advertising or publicity relating to this software or products derived from
it.  This software may be referred to only as "the Independent JPEG Group's
software".

We specifically permit and encourage the use of this software as the basis of
commercial products, provided that all warranty or liability claims are
assumed by the product vendor.

```

### Mesa 3-D Graphics Library v21.0.3

#### Mesa License

```
Copyright (C) 1999-2007  Brian Paul   All Rights Reserved.

Permission is hereby granted, free of charge, to any person obtaining a
copy of this software and associated documentation files (the "Software"),
to deal in the Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, sublicense,
and/or sell copies of the Software, and to permit persons to whom the
Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included
in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL
THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

Attention, Contributors

When contributing to the Mesa project you must agree to the licensing terms
of the component to which you're contributing.
The following section lists the primary components of the Mesa distribution
and their respective licenses.
Mesa Component Licenses



Component         Location               License
------------------------------------------------------------------
Main Mesa code    src/mesa/              MIT
Device drivers    src/mesa/drivers/*     MIT, generally

Gallium code      src/gallium/           MIT

Ext headers       GL/glext.h             Khronos
                  GL/glxext.h            Khronos
                  GL/wglext.h            Khronos
                  KHR/khrplatform.h      Khronos

*****************************************************************************

----
include/GL/gl.h :


 Mesa 3-D graphics library

 Copyright (C) 1999-2006  Brian Paul   All Rights Reserved.
 Copyright (C) 2009  VMware, Inc.  All Rights Reserved.

 Permission is hereby granted, free of charge, to any person obtaining a
 copy of this software and associated documentation files (the "Software"),
 to deal in the Software without restriction, including without limitation
 the rights to use, copy, modify, merge, publish, distribute, sublicense,
 and/or sell copies of the Software, and to permit persons to whom the
 Software is furnished to do so, subject to the following conditions:

 The above copyright notice and this permission notice shall be included
 in all copies or substantial portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
 OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL
 THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR
 OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE,
 ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 OTHER DEALINGS IN THE SOFTWARE.

 *****************************************************************************

----
include/GL/glext.h
include/GL/glxext.h
include/GL/wglxext.h :


 Copyright (c) 2013 - 2018 The Khronos Group Inc.

 Permission is hereby granted, free of charge, to any person obtaining a
 copy of this software and/or associated documentation files (the
 "Materials"), to deal in the Materials without restriction, including
 without limitation the rights to use, copy, modify, merge, publish,
 distribute, sublicense, and/or sell copies of the Materials, and to
 permit persons to whom the Materials are furnished to do so, subject to
 the following conditions:

 The above copyright notice and this permission notice shall be included
 in all copies or substantial portions of the Materials.

 THE MATERIALS ARE PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
 CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
 TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 MATERIALS OR THE USE OR OTHER DEALINGS IN THE MATERIALS.

 *****************************************************************************

----
include/KHR/khrplatform.h :

 Copyright (c) 2008 - 2018 The Khronos Group Inc.

 Permission is hereby granted, free of charge, to any person obtaining a
 copy of this software and/or associated documentation files (the
 "Materials"), to deal in the Materials without restriction, including
 without limitation the rights to use, copy, modify, merge, publish,
 distribute, sublicense, and/or sell copies of the Materials, and to
 permit persons to whom the Materials are furnished to do so, subject to
 the following conditions:

 The above copyright notice and this permission notice shall be included
 in all copies or substantial portions of the Materials.

 THE MATERIALS ARE PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
 IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY
 CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
 TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 MATERIALS OR THE USE OR OTHER DEALINGS IN THE MATERIALS.

 *****************************************************************************

```

### PipeWire 1.3.81

#### PipeWire license:

All PipeWire header files are licensed under the MIT License:


<pre>
Copyright © 2018-2023 Wim Taymans

Permission is hereby granted, free of charge, to any person obtaining a
copy of this software and associated documentation files (the "Software"),
to deal in the Software without restriction, including without limitation
the rights to use, copy, modify, merge, publish, distribute, sublicense,
and/or sell copies of the Software, and to permit persons to whom the
Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice (including the next
paragraph) shall be included in all copies or substantial portions of the
Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL
THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
DEALINGS IN THE SOFTWARE.
</pre>

The below copyright applies to the following files:

spa/include/spa/monitor/type-info.h
```
Copyright © 2021 Collabora Ltd.
```

spa/include/spa/utils/string.h
```
Copyright © 2021 Red Hat, Inc.
```

spa/utils/cleanup.h:
```
Copyright © 2023 PipeWire authors
```
