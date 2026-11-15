# Regras do R8 para o release.
#
# Room, Glance, DataStore, Navigation e coroutines trazem as próprias regras (consumer rules)
# nas bibliotecas; a exceção são os widgets abaixo. Os enums persistidos pelo nome
# (ReminderRepeat; RoutineIcon e RoutineColor via fromKey) continuam seguros: name()/valueOf()
# e a busca por name impedem o R8 de trocar os nomes ou convertê-los em inteiros.
#
# O Glance acha os widgets de um tipo pela classe (getGlanceIds/updateAll, guardando o nome
# dela). Sem esta regra o R8 funde TasksWidget e SummaryWidget numa classe só, e o updateAll
# de um redesenha o outro com o conteúdo errado. Manter o nome também o deixa estável entre
# versões.
#
# Construtor sem argumentos explícito nas três regras abaixo: desde o AGP 9 o R8 aplica o modo
# estrito (android.r8.strictFullModeForKeepRules), em que "-keep class X" sem membros mantém só a
# classe, não o construtor padrão. O AGP 8 o mantinha implicitamente.
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { public <init>(); }

# As ações dos widgets são instanciadas pelo nome da classe (reflexão, construtor sem
# argumentos); sem isto o R8 poderia fundir CompleteTaskAction e CompleteRoutineAction pelo mesmo
# motivo, ou remover o construtor.
-keep class * implements androidx.glance.appwidget.action.ActionCallback { public <init>(); }

# O WorkManager (work-runtime 2.7.1, que o Glance usa nas sessões dos widgets) instancia o
# InputMerger de cada job pelo nome, com o construtor sem argumentos. A regra da própria
# biblioteca ("-keep class * extends androidx.work.InputMerger", sem membros) não o mantém mais
# no modo estrito: sem ele nenhum worker roda e os widgets ficam carregando para sempre
# (InstantiationException em WM-InputMerger).
-keep class * extends androidx.work.InputMerger { public <init>(); }

# Mantém nomes de arquivo e linhas nos stack traces para diagnosticar crashes do release.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
