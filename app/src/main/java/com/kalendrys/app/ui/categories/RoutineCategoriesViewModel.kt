package com.kalendrys.app.ui.categories

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kalendrys.app.KalendrysApp
import com.kalendrys.app.data.local.entity.RoutineCategory
import com.kalendrys.app.data.repository.RoutineCategoryRepository
import com.kalendrys.app.domain.RoutineCategoryKind
import com.kalendrys.app.domain.RoutineColor
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** CRUD das categorias de rotina, com o tipo (kind) que liga a categoria ao módulo de item. */
class RoutineCategoriesViewModel(
    private val repository: RoutineCategoryRepository,
) : ViewModel() {

    val categories: StateFlow<List<RoutineCategory>> =
        repository.categories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun create(name: String, color: RoutineColor, kind: RoutineCategoryKind) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.create(name, color.name, kind) }
    }

    fun update(category: RoutineCategory, name: String, color: RoutineColor, kind: RoutineCategoryKind) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.update(category, name, color.name, kind) }
    }

    fun delete(category: RoutineCategory) {
        viewModelScope.launch { repository.delete(category) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as KalendrysApp
                RoutineCategoriesViewModel(app.routineCategoryRepository)
            }
        }
    }
}
