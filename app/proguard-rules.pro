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
-keep class * extends androidx.glance.appwidget.GlanceAppWidget

# As ações dos widgets são instanciadas pelo nome da classe; sem isto o R8 poderia fundir
# CompleteTaskAction e CompleteRoutineAction pelo mesmo motivo.
-keep class * implements androidx.glance.appwidget.action.ActionCallback

# Mantém nomes de arquivo e linhas nos stack traces para diagnosticar crashes do release.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
