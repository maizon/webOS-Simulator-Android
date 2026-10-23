/* Linux ARMv7 standalone ELF probe, no libc or Android linker. */
.syntax unified
.text
.global _start
_start:
    mov r0, #1
    adr r1, message
    mov r2, #22
    mov r7, #4
    svc #0
    mov r0, #0
    mov r7, #1
    svc #0
message:
    .ascii "MAIZON_LINUX_GUEST_OK\n"
message_end:
