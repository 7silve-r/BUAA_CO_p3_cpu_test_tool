ori $t0, $zero, 1
ori $t1, $zero, 2
beq $t0, $t1, wrong
ori $s0, $zero, 1
beq $t0, $t0, forward
wrong: ori $s0, $zero, 0xbad
forward: ori $t2, $zero, 3
loop: sub $t2, $t2, $t0
beq $t2, $zero, finished
beq $zero, $zero, loop
finished: beq $t0, $t1, loop
beq $t0, $t1, finished
beq $zero, $zero, after_zero_offset
after_zero_offset: ori $s1, $zero, 0xbeef
.expect reg $s0 1
.expect reg $s1 0xbeef
.expect reg $t2 0
