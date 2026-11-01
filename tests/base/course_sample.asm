ori $a0, $zero, 123
ori $a1, $a0, 456
lui $a2, 123
lui $a3, 0xffff
ori $a3, $a3, 0xffff
add $s0, $a0, $a2
add $s1, $a0, $a3
add $s2, $a3, $a3
ori $t0, $zero, 0
sw $a0, 0($t0)
sw $a1, 4($t0)
sw $a2, 8($t0)
sw $a3, 12($t0)
sw $s0, 16($t0)
sw $s1, 20($t0)
sw $s2, 24($t0)
lw $a0, 0($t0)
lw $a1, 12($t0)
sw $a0, 28($t0)
sw $a1, 32($t0)
ori $a0, $zero, 1
ori $a1, $zero, 2
ori $a2, $zero, 1
beq $a0, $a1, loop1
beq $a0, $a2, loop2
loop1: sw $a0, 36($t0)
loop2: sw $a1, 40($t0)
.expect mem 0 0x7b
.expect mem 4 0x1fb
.expect mem 8 0x007b0000
.expect mem 12 0xffffffff
.expect mem 16 0x007b007b
.expect mem 20 0x7a
.expect mem 24 0xfffffffe
.expect mem 28 0x7b
.expect mem 32 0xffffffff
.expect mem 36 0
.expect mem 40 2
