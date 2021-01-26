/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.sql.planner;

import io.trino.Session;
import io.trino.spi.expression.ConnectorExpression;
import io.trino.spi.expression.Constant;
import io.trino.spi.expression.FieldDereference;
import io.trino.spi.expression.Variable;
import io.trino.spi.type.Decimals;
import io.trino.spi.type.RowType;
import io.trino.spi.type.Type;
import io.trino.sql.tree.AstVisitor;
import io.trino.sql.tree.BinaryLiteral;
import io.trino.sql.tree.BooleanLiteral;
import io.trino.sql.tree.CharLiteral;
import io.trino.sql.tree.DecimalLiteral;
import io.trino.sql.tree.DereferenceExpression;
import io.trino.sql.tree.DoubleLiteral;
import io.trino.sql.tree.Expression;
import io.trino.sql.tree.Identifier;
import io.trino.sql.tree.LongLiteral;
import io.trino.sql.tree.NodeRef;
import io.trino.sql.tree.NullLiteral;
import io.trino.sql.tree.StringLiteral;
import io.trino.sql.tree.SymbolReference;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

public final class ConnectorExpressionTranslator
{
    private ConnectorExpressionTranslator() {}

    public static Expression translate(ConnectorExpression expression, Map<String, Symbol> variableMappings, LiteralEncoder literalEncoder)
    {
        return new ConnectorToSqlExpressionTranslator(variableMappings, literalEncoder).translate(expression);
    }

    public static Optional<ConnectorExpression> translate(Session session, Expression expression, TypeAnalyzer types, TypeProvider inputTypes)
    {
        return new SqlToConnectorExpressionTranslator(types.getTypes(session, inputTypes, expression))
                .process(expression);
    }

    private static class ConnectorToSqlExpressionTranslator
    {
        private final Map<String, Symbol> variableMappings;
        private final LiteralEncoder literalEncoder;

        public ConnectorToSqlExpressionTranslator(Map<String, Symbol> variableMappings, LiteralEncoder literalEncoder)
        {
            this.variableMappings = requireNonNull(variableMappings, "variableMappings is null");
            this.literalEncoder = requireNonNull(literalEncoder, "literalEncoder is null");
        }

        public Expression translate(ConnectorExpression expression)
        {
            if (expression instanceof Variable) {
                return variableMappings.get(((Variable) expression).getName()).toSymbolReference();
            }

            if (expression instanceof Constant) {
                return literalEncoder.toExpression(((Constant) expression).getValue(), expression.getType());
            }

            if (expression instanceof FieldDereference) {
                FieldDereference dereference = (FieldDereference) expression;

                RowType type = (RowType) dereference.getTarget().getType();
                String name = type.getFields().get(dereference.getField()).getName().get();
                return new DereferenceExpression(translate(dereference.getTarget()), new Identifier(name));
            }

            throw new UnsupportedOperationException("Expression type not supported: " + expression.getClass().getName());
        }
    }

    static class SqlToConnectorExpressionTranslator
            extends AstVisitor<Optional<ConnectorExpression>, Void>
    {
        private final Map<NodeRef<Expression>, Type> types;

        public SqlToConnectorExpressionTranslator(Map<NodeRef<Expression>, Type> types)
        {
            this.types = requireNonNull(types, "types is null");
        }

        @Override
        protected Optional<ConnectorExpression> visitSymbolReference(SymbolReference node, Void context)
        {
            return Optional.of(new Variable(node.getName(), typeOf(node)));
        }

        @Override
        protected Optional<ConnectorExpression> visitBooleanLiteral(BooleanLiteral node, Void context)
        {
            return Optional.of(new Constant(node.getValue(), typeOf(node)));
        }

        @Override
        protected Optional<ConnectorExpression> visitStringLiteral(StringLiteral node, Void context)
        {
            return Optional.of(new Constant(node.getSlice(), typeOf(node)));
        }

        @Override
        protected Optional<ConnectorExpression> visitDoubleLiteral(DoubleLiteral node, Void context)
        {
            return Optional.of(new Constant(node.getValue(), typeOf(node)));
        }

        @Override
        protected Optional<ConnectorExpression> visitDecimalLiteral(DecimalLiteral node, Void context)
        {
            return Optional.of(new Constant(Decimals.parse(node.getValue()).getObject(), typeOf(node)));
        }

        @Override
        protected Optional<ConnectorExpression> visitCharLiteral(CharLiteral node, Void context)
        {
            return Optional.of(new Constant(node.getSlice(), typeOf(node)));
        }

        @Override
        protected Optional<ConnectorExpression> visitBinaryLiteral(BinaryLiteral node, Void context)
        {
            return Optional.of(new Constant(node.getValue(), typeOf(node)));
        }

        @Override
        protected Optional<ConnectorExpression> visitLongLiteral(LongLiteral node, Void context)
        {
            return Optional.of(new Constant(node.getValue(), typeOf(node)));
        }

        @Override
        protected Optional<ConnectorExpression> visitNullLiteral(NullLiteral node, Void context)
        {
            return Optional.of(new Constant(null, typeOf(node)));
        }

        @Override
        protected Optional<ConnectorExpression> visitDereferenceExpression(DereferenceExpression node, Void context)
        {
            Optional<ConnectorExpression> translatedBase = process(node.getBase());
            if (translatedBase.isEmpty()) {
                return Optional.empty();
            }

            RowType rowType = (RowType) typeOf(node.getBase());
            String fieldName = node.getField().getValue();
            List<RowType.Field> fields = rowType.getFields();
            int index = -1;
            for (int i = 0; i < fields.size(); i++) {
                RowType.Field field = fields.get(i);
                if (field.getName().isPresent() && field.getName().get().equalsIgnoreCase(fieldName)) {
                    checkArgument(index < 0, "Ambiguous field %s in type %s", field, rowType.getDisplayName());
                    index = i;
                }
            }

            checkState(index >= 0, "could not find field name: %s", node.getField());

            return Optional.of(new FieldDereference(typeOf(node), translatedBase.get(), index));
        }

        @Override
        protected Optional<ConnectorExpression> visitExpression(Expression node, Void context)
        {
            return Optional.empty();
        }

        private Type typeOf(Expression node)
        {
            return types.get(NodeRef.of(node));
        }
    }
}
