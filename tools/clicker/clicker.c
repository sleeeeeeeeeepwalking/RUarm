/*
 * ruarm_clicker.exe — runs next to an installer inside RUarm's headless Wine.
 * Every 300 ms it looks for dialog windows (message boxes, class "#32770"), prints their text
 * to stdout (UTF-8, one line per dialog: "MSG|<text>") and presses OK / Yes / the first button.
 * Built freestanding (no CRT) for aarch64-windows, see build.sh.
 */
typedef unsigned short WCHAR;
typedef void *HWND;
typedef void *HANDLE;
typedef int BOOL;
typedef unsigned int UINT;
typedef unsigned long DWORD;
typedef unsigned long long WPARAM;
typedef long long LPARAM;
typedef long long LRESULT;
typedef BOOL (*WNDENUMPROC)(HWND, LPARAM);

#define WINAPI __stdcall
#define DLLIMPORT __declspec(dllimport)
#define WM_COMMAND 0x0111
#define BN_CLICKED 0
#define STD_OUTPUT_HANDLE ((DWORD)-11)
#define CP_UTF8 65001

DLLIMPORT BOOL WINAPI EnumWindows(WNDENUMPROC, LPARAM);
DLLIMPORT BOOL WINAPI EnumChildWindows(HWND, WNDENUMPROC, LPARAM);
DLLIMPORT int WINAPI GetClassNameW(HWND, WCHAR *, int);
DLLIMPORT int WINAPI GetWindowTextW(HWND, WCHAR *, int);
DLLIMPORT int WINAPI GetDlgCtrlID(HWND);
DLLIMPORT HWND WINAPI GetDlgItem(HWND, int);
DLLIMPORT BOOL WINAPI PostMessageW(HWND, UINT, WPARAM, LPARAM);
DLLIMPORT BOOL WINAPI IsWindow(HWND);
DLLIMPORT void WINAPI Sleep(DWORD);
DLLIMPORT HANDLE WINAPI GetStdHandle(DWORD);
DLLIMPORT BOOL WINAPI WriteFile(HANDLE, const void *, DWORD, DWORD *, void *);
DLLIMPORT int WINAPI WideCharToMultiByte(UINT, DWORD, const WCHAR *, int, char *, int, const char *, BOOL *);
DLLIMPORT void WINAPI ExitProcess(UINT);

static WCHAR text[2048];
static int textLen;
static int firstButton;
static HWND handled[64];
static int handledCount;

static int is_class(HWND h, const char *name) {
    WCHAR cls[64];
    int n = GetClassNameW(h, cls, 64), i = 0;
    for (; i < n && name[i]; i++) {
        WCHAR a = cls[i], b = (WCHAR)name[i];
        if (a >= 'A' && a <= 'Z') a += 32;
        if (b >= 'A' && b <= 'Z') b += 32;
        if (a != b) return 0;
    }
    return i == n && name[i] == 0;
}

static BOOL child_proc(HWND h, LPARAM unused) {
    (void)unused;
    if (is_class(h, "Static")) {
        WCHAR buf[1024];
        int n = GetWindowTextW(h, buf, 1024);
        for (int i = 0; i < n && textLen < 2000; i++) {
            WCHAR c = buf[i];
            text[textLen++] = (c == '\r' || c == '\n') ? ' ' : c;
        }
        if (n > 0 && textLen < 2000) text[textLen++] = ' ';
    } else if (is_class(h, "Button") && firstButton == 0) {
        firstButton = GetDlgCtrlID(h);
    }
    return 1;
}

static void out(const char *s, int n) {
    DWORD w;
    WriteFile(GetStdHandle(STD_OUTPUT_HANDLE), s, (DWORD)n, &w, 0);
}

static BOOL top_proc(HWND h, LPARAM unused) {
    (void)unused;
    if (!is_class(h, "#32770")) return 1;
    for (int i = 0; i < handledCount; i++) if (handled[i] == h) return 1;
    if (handledCount < 64) handled[handledCount++] = h;

    textLen = 0;
    firstButton = 0;
    EnumChildWindows(h, child_proc, 0);
    char utf8[6200];
    int n = WideCharToMultiByte(CP_UTF8, 0, text, textLen, utf8 + 4, 6000, 0, 0);
    utf8[0] = 'M'; utf8[1] = 'S'; utf8[2] = 'G'; utf8[3] = '|';
    utf8[4 + n] = '\n';
    out(utf8, n + 5);

    /* OK (1), Yes (6), Continue (11), Retry? no — otherwise the first button */
    int id = GetDlgItem(h, 1) ? 1 : GetDlgItem(h, 6) ? 6 : GetDlgItem(h, 11) ? 11 : firstButton;
    if (id) PostMessageW(h, WM_COMMAND, (WPARAM)(id | (BN_CLICKED << 16)), (LPARAM)GetDlgItem(h, id));
    return 1;
}

void start(void) {
    out("READY\n", 6);
    for (;;) {
        /* forget closed dialogs so a reused handle is handled again */
        for (int i = 0; i < handledCount; i++)
            if (!IsWindow(handled[i])) handled[i--] = handled[--handledCount];
        EnumWindows(top_proc, 0);
        Sleep(300);
    }
}
