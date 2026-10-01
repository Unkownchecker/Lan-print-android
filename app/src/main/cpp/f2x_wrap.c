/*
 * f2x_wrap.c -- runs the UNMODIFIED foo2xqx.c (from the foo2zjs project) as a
 * library function instead of a command-line filter.
 *
 * foo2xqx is a stdin->stdout filter that calls exit() and keeps its options in
 * global variables. To call it repeatedly from inside an app process we:
 *   - #include the original source into this translation unit (so we can see
 *     and reset its globals),
 *   - point its stdin/stdout at files we open ourselves,
 *   - turn exit() into a longjmp back to f2x_run(),
 *   - swap getopt() for a small self-contained one (no hidden global state),
 *   - rename a few generic symbols so they can't collide with libc.
 *
 * The original source is not edited, so its output is byte-for-byte what the
 * real foo2xqx produces (verified in tests against the reference binary).
 *
 * License: GPL-2.0-or-later, same as foo2zjs (see COPYING).
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <setjmp.h>
#include <unistd.h>

static FILE *f2x_in;
static FILE *f2x_out;
static jmp_buf f2x_jmp;
static volatile int f2x_exit_code;

static char *f2x_optarg;
static int f2x_optind;
static int f2x_optpos;

static void f2x_exit(int code) __attribute__((noreturn));
static void f2x_exit(int code)
{
    f2x_exit_code = code;
    longjmp(f2x_jmp, 1);
}

/* Minimal POSIX-style getopt (supports clustered flags and "x:" arguments). */
static int f2x_getopt(int argc, char *const argv[], const char *optstring)
{
    const char *a;
    const char *p;
    int c;

    f2x_optarg = NULL;
    if (f2x_optpos == 0) {
        if (f2x_optind >= argc) return -1;
        a = argv[f2x_optind];
        if (a[0] != '-' || a[1] == 0) return -1;
        if (a[1] == '-' && a[2] == 0) { f2x_optind++; return -1; }
        f2x_optpos = 1;
    }
    a = argv[f2x_optind];
    c = (unsigned char) a[f2x_optpos++];
    p = strchr(optstring, c);
    if (!p || c == ':') {
        if (!a[f2x_optpos]) { f2x_optind++; f2x_optpos = 0; }
        return '?';
    }
    if (p[1] == ':') {
        if (a[f2x_optpos]) {
            f2x_optarg = (char *) a + f2x_optpos;
        } else if (f2x_optind + 1 < argc) {
            f2x_optarg = argv[f2x_optind + 1];
            f2x_optind++;
        } else {
            f2x_optind++; f2x_optpos = 0;
            return '?';
        }
        f2x_optind++;
        f2x_optpos = 0;
        return c;
    }
    if (!a[f2x_optpos]) { f2x_optind++; f2x_optpos = 0; }
    return c;
}

#undef stdin
#undef stdout
#define stdin  f2x_in
#define stdout f2x_out
#define exit(c) f2x_exit(c)
#define getopt f2x_getopt
#define optarg f2x_optarg
#define optind f2x_optind
#define main   foo2xqx_main
#define error  f2x_error
#define debug  f2x_debug
#define usage  f2x_usage

#include "foo2zjs/foo2xqx.c"

/* Put every option/global back to its compile-time default. */
static void f2x_reset(void)
{
    Debug = 0;
    ResX = 600;
    ResY = 600;
    Bpp = 1;
    PaperCode = DMPAPER_LETTER;
    PageWidth = 1200 * 8.5;
    PageHeight = 600 * 11;
    UpperLeftX = 0;
    UpperLeftY = 0;
    LowerRightX = 0;
    LowerRightY = 0;
    Copies = 1;
    Duplex = DMDUPLEX_OFF;
    SourceCode = DMBIN_AUTO;
    MediaCode = DMMEDIA_PLAIN;
    Username = NULL;
    Filename = NULL;
    Mode = 0;
    Color2Mono = 0;
    BlackClears = 0;
    AllIsBlack = 0;
    OutputStartPlane = 1;
    ExtraPad = 16;
    LogicalOffsetX = 0;
    LogicalOffsetY = 0;
    LogicalClip = LOGICAL_CLIP_X | LOGICAL_CLIP_Y;
    SaveToner = 0;
    PageNum = 0;
    RealWidth = 0;
    EconoMode = 0;
    PrintDensity = 3;
    IsCUPS = 0;
    EvenPages = NULL;
    memset(SeekRec, 0, sizeof(SeekRec));
    SeekIndex = 0;
    DuplexPause = 0;
    AnyColor = 0;
}

/*
 * Convert the pbmraw file at in_path into an XQX stream at out_path, using
 * foo2xqx command-line style arguments (argv[0] is the program name).
 * Returns 0 on success, otherwise the exit code foo2xqx would have used
 * (or a negative number if the files couldn't be opened).
 */
int f2x_run(int argc, char **argv, const char *in_path, const char *out_path)
{
    int rc;

    f2x_in = fopen(in_path, "rb");
    if (!f2x_in) return -1;
    f2x_out = fopen(out_path, "wb");
    if (!f2x_out) { fclose(f2x_in); f2x_in = NULL; return -2; }

    f2x_reset();
    f2x_optind = 1;
    f2x_optpos = 0;
    f2x_optarg = NULL;
    f2x_exit_code = 99;

    if (setjmp(f2x_jmp) == 0) {
        foo2xqx_main(argc, argv);
        f2x_exit_code = 98; /* main() should always end via exit() */
    }
    rc = f2x_exit_code;

    fclose(f2x_in);
    f2x_in = NULL;
    fclose(f2x_out);
    f2x_out = NULL;
    return rc;
}
