lui $t1, 0x1234
ori $t1, $t1, 0x5678
sw $t1, 0($zero)
ori $t0, $zero, 0x3000
sw $t1, -4($t0)
ori $t2, $zero, 0xbad
lw $t2, -4($t0)
# Negative base, positive offset.
lui $t3, 0xffff
ori $t3, $t3, 0xfffc
lw $t4, 4($t3)
# Smallest signed offset; effective address zero.
ori $t5, $zero, 0x8000
sw $t1, -32768($t5)
lw $t6, -32768($t5)
# Largest signed offset; effective address zero.
lui $t5, 0xffff
ori $t5, $t5, 0x8001
lw $t7, 32767($t5)
# Destination aliases the address base.
ori $t0, $zero, 0
lw $t0, 0($t0)
lw $zero, 0($zero)
nop
.expect reg $t0 0x12345678
.expect reg $t2 0x12345678
.expect reg $t4 0x12345678
.expect reg $t6 0x12345678
.expect reg $t7 0x12345678
.expect reg $zero 0
.expect mem 0 0x12345678
.expect mem 0x2ffc 0x12345678
