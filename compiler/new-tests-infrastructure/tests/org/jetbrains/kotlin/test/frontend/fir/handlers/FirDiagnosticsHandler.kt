/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.test.frontend.fir.handlers

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.checkers.diagnostics.factories.DebugInfoDiagnosticFactory1
import org.jetbrains.kotlin.checkers.utils.TypeOfCall
import org.jetbrains.kotlin.diagnostics.rendering.Renderers
import org.jetbrains.kotlin.fir.*
import org.jetbrains.kotlin.fir.analysis.diagnostics.*
import org.jetbrains.kotlin.fir.declarations.FirCallableMemberDeclaration
import org.jetbrains.kotlin.fir.declarations.FirFile
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.expressions.FirExpressionWithSmartcast
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.references.FirNamedReference
import org.jetbrains.kotlin.fir.references.FirResolvedNamedReference
import org.jetbrains.kotlin.fir.symbols.AbstractFirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirFunctionSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.FirTypeRef
import org.jetbrains.kotlin.fir.types.coneTypeSafe
import org.jetbrains.kotlin.fir.visitors.FirDefaultVisitorVoid
import org.jetbrains.kotlin.name.FqNameUnsafe
import org.jetbrains.kotlin.test.directives.DiagnosticsDirectives
import org.jetbrains.kotlin.test.directives.DirectivesContainer
import org.jetbrains.kotlin.test.frontend.fir.FirSourceArtifact
import org.jetbrains.kotlin.test.model.TestFile
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.services.*
import org.jetbrains.kotlin.util.OperatorNameConventions
import org.jetbrains.kotlin.utils.addIfNotNull

class FirDiagnosticsHandler(testServices: TestServices) : FirAnalysisHandler(testServices) {
    private val globalMetadataInfoHandler: GlobalMetadataInfoHandler
        get() = testServices.globalMetadataInfoHandler

    private val diagnosticsService: DiagnosticsService
        get() = testServices.diagnosticsService

    override val directivesContainers: List<DirectivesContainer> =
        listOf(DiagnosticsDirectives)

    override val additionalServices: List<ServiceRegistrationData> =
        listOf(service(::DiagnosticsService))

    override fun processModule(module: TestModule, info: FirSourceArtifact) {
        val diagnosticsPerFile = info.firAnalyzerFacade.runCheckers()

        for (file in module.files) {
            val firFile = info.firFiles[file] ?: continue
            val diagnostics = diagnosticsPerFile[firFile] ?: continue
            val diagnosticsMetadataInfos = diagnostics.mapNotNull { diagnostic ->
                if (!diagnosticsService.shouldRenderDiagnostic(module, diagnostic.factory.name)) return@mapNotNull null
                diagnostic.toMetaInfo(file)
            }
            globalMetadataInfoHandler.addMetadataInfosForFile(file, diagnosticsMetadataInfos)
            collectDebugInfoDiagnostics(file, firFile)
        }
    }

    private fun FirDiagnostic<*>.toMetaInfo(file: TestFile, forceRenderArguments: Boolean = false): FirDiagnosticCodeMetaInfo {
        val metaInfo = FirDiagnosticCodeMetaInfo(this, FirMetaInfoUtils.renderDiagnosticNoArgs)
        val shouldRenderArguments = forceRenderArguments || globalMetadataInfoHandler.getExistingMetaInfosForActualMetadata(file, metaInfo)
            .any { it.description != null }
        if (shouldRenderArguments) {
            metaInfo.replaceRenderConfiguration(FirMetaInfoUtils.renderDiagnosticWithArgs)
        }
        return metaInfo
    }

    private fun collectDebugInfoDiagnostics(
        testFile: TestFile,
        firFile: FirFile,
    ) {
        val result = mutableListOf<FirDiagnostic<*>>()
        val diagnosedRangesToDiagnosticNames = globalMetadataInfoHandler.getExistingMetaInfosForFile(testFile).groupBy(
            keySelector = { it.start..it.end },
            valueTransform = { it.tag }
        ).mapValues { (_, it) -> it.toSet() }
        object : FirDefaultVisitorVoid() {
            override fun visitElement(element: FirElement) {
                if (element is FirExpression) {
                    result.addIfNotNull(
                        createExpressionTypeDiagnosticIfExpected(
                            element, diagnosedRangesToDiagnosticNames
                        )
                    )
                }

                element.acceptChildren(this)
            }

            override fun visitFunctionCall(functionCall: FirFunctionCall) {
                result.addIfNotNull(
                    createCallDiagnosticIfExpected(functionCall, functionCall.calleeReference, diagnosedRangesToDiagnosticNames)
                )

                super.visitFunctionCall(functionCall)
            }
        }.let(firFile::accept)
        globalMetadataInfoHandler.addMetadataInfosForFile(testFile, result.map { it.toMetaInfo(testFile, forceRenderArguments = true) })
    }

    fun createExpressionTypeDiagnosticIfExpected(
        element: FirExpression,
        diagnosedRangesToDiagnosticNames: Map<IntRange, Set<String>>
    ): FirDiagnosticWithParameters1<FirSourceElement, String>? =
        DebugInfoDiagnosticFactory1.EXPRESSION_TYPE.createDebugInfoDiagnostic(element, diagnosedRangesToDiagnosticNames) {
            element.typeRef.renderAsString((element as? FirExpressionWithSmartcast)?.originalType)
        }

    private fun FirTypeRef.renderAsString(originalTypeRef: FirTypeRef?): String {
        val type = coneTypeSafe<ConeKotlinType>() ?: return "Type is unknown"
        val rendered = type.renderForDebugInfo()
        val originalTypeRendered = originalTypeRef?.coneTypeSafe<ConeKotlinType>()?.renderForDebugInfo() ?: return rendered

        return "$rendered & $originalTypeRendered"
    }

    private fun createCallDiagnosticIfExpected(
        element: FirElement,
        reference: FirNamedReference,
        diagnosedRangesToDiagnosticNames: Map<IntRange, Set<String>>
    ): FirDiagnosticWithParameters1<FirSourceElement, String>? =
        DebugInfoDiagnosticFactory1.CALL.createDebugInfoDiagnostic(element, diagnosedRangesToDiagnosticNames) {
            val resolvedSymbol = (reference as? FirResolvedNamedReference)?.resolvedSymbol
            val fqName = resolvedSymbol?.fqNameUnsafe()
            Renderers.renderCallInfo(fqName, getTypeOfCall(reference, resolvedSymbol))
        }

    private fun DebugInfoDiagnosticFactory1.createDebugInfoDiagnostic(
        element: FirElement,
        diagnosedRangesToDiagnosticNames: Map<IntRange, Set<String>>,
        argument: () -> String,
    ): FirDiagnosticWithParameters1<FirSourceElement, String>? {
        val sourceElement = element.source ?: return null
        if (diagnosedRangesToDiagnosticNames[sourceElement.startOffset..sourceElement.endOffset]?.contains(this.name) != true) return null

        val argumentText = argument()
        return when (sourceElement) {
            is FirPsiSourceElement<*> -> FirPsiDiagnosticWithParameters1(
                sourceElement,
                argumentText,
                severity,
                FirDiagnosticFactory1(
                    name,
                    severity,
                    this
                )
            )
            is FirLightSourceElement -> FirLightDiagnosticWithParameters1(
                sourceElement,
                argumentText,
                severity,
                FirDiagnosticFactory1<FirSourceElement, PsiElement, String>(
                    name,
                    severity,
                    this
                )
            )
        }
    }

    private fun getTypeOfCall(
        reference: FirNamedReference,
        resolvedSymbol: AbstractFirBasedSymbol<*>?
    ): String {
        if (resolvedSymbol == null) return TypeOfCall.UNRESOLVED.nameToRender

        if ((resolvedSymbol as? FirFunctionSymbol)?.callableId?.callableName == OperatorNameConventions.INVOKE
            && reference.name != OperatorNameConventions.INVOKE
        ) {
            return TypeOfCall.VARIABLE_THROUGH_INVOKE.nameToRender
        }

        return when (val fir = resolvedSymbol.fir) {
            is FirProperty -> {
                TypeOfCall.PROPERTY_GETTER.nameToRender
            }
            is FirFunction<*> -> buildString {
                if (fir is FirCallableMemberDeclaration<*>) {
                    if (fir.status.isInline) append("inline ")
                    if (fir.status.isInfix) append("infix ")
                    if (fir.status.isOperator) append("operator ")
                    if (fir.receiverTypeRef != null) append("extension ")
                }
                append(TypeOfCall.FUNCTION.nameToRender)
            }
            else -> TypeOfCall.OTHER.nameToRender
        }
    }

    private fun AbstractFirBasedSymbol<*>.fqNameUnsafe(): FqNameUnsafe? = when (this) {
        is FirClassLikeSymbol<*> -> classId.asSingleFqName().toUnsafe()
        is FirCallableSymbol<*> -> callableId.asFqNameForDebugInfo().toUnsafe()
        else -> null
    }

    override fun processAfterAllModules() {}
}
