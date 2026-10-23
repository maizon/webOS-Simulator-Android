/* Linux ARM64 standalone ELF probe, no libraries or linker dependency. */
.text
.global _start
_start:
    mov x0, #1
    adr x1, message
    mov x2, #22
    mov x8, #64
    svc #0
    mov x0, #0
    mov x8, #93
    svc #0
message:
    .ascii "MAIZON_LINUX_GUEST_OK\n"
message_end:
