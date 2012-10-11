int foo();
int bar(int x);

%{
static int foo() { return 3; }
static int bar(int x) { return 30 * x; }
%}
