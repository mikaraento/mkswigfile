(use 'mkswigfile)

(mkswigfile "example.i")

(prn (foo))

(prn (bar (int 3)))
