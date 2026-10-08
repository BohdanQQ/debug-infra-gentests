(set-logic QF_LRA)

(declare-const a Real)
(declare-const b Real)
(declare-const c Real)

(assert (<= a b))
(assert (<= b c))
(assert (<= c a))
(assert (= 6 (+ a b c)))

(check-sat)
