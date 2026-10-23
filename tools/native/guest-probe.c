/* CI Linux guest ELF probe: runs inside PRoot on Android or QEMU in CI. */
#include <stdio.h>
#include <stdint.h>
#include <unistd.h>
int main(void) {
    volatile uint64_t v = UINT64_C(0x4d41495a4f4e);
    if (v != UINT64_C(0x4d41495a4f4e)) return 81;
    if (puts("MAIZON_LINUX_GUEST_OK") < 0) return 82;
    return fflush(stdout) == 0 ? 0 : 83;
}
