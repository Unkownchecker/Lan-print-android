cmake_minimum_required(VERSION 3.22.1)
project(f2xnative C)

# foo2xqx (HP LaserJet P1005/P1006/P1007/P1008/P1505 raster->XQX converter) and
# JBIG-KIT, both from the foo2zjs project. GPL-2.0-or-later, see foo2zjs/COPYING.
add_library(f2xnative SHARED
    f2x_jni.c
    f2x_wrap.c
    foo2zjs/jbig.c
    foo2zjs/jbig_ar.c)

target_compile_options(f2xnative PRIVATE -O2 -fvisibility=hidden -Wno-unused-function -Wno-unused-variable)
target_link_options(f2xnative PRIVATE -Wl,-Bsymbolic -Wl,-z,max-page-size=16384)
