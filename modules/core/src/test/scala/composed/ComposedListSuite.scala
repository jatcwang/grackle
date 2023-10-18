// Copyright (c) 2016-2023 Association of Universities for Research in Astronomy, Inc. (AURA)
// Copyright (c) 2016-2023 Grackle Contributors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package composed

import cats.effect.IO
import cats.implicits._
import io.circe.literal._
import munit.CatsEffectSuite

import grackle._
import grackle.syntax._
import Query._
import Predicate._, Value._
import QueryCompiler._

object CollectionData {
  case class Collection(name: String, itemIds: List[String])
  val collections =
    List(
      Collection("AB", List("A", "B")),
      Collection("BC", List("B", "C"))
    )
}

object CollectionMapping extends ValueMapping[IO] {
  import CollectionData._

  val schema =
    schema"""
      type Query {
        collection: Collection!
        collections: [Collection!]!
        collectionByName(name: String!): Collection
      }
      type Collection {
        name: String!
        itemIds: [String!]!
      }
    """

  val QueryType = schema.ref("Query")
  val CollectionType = schema.ref("Collection")

  val typeMappings =
    List(
      ValueObjectMapping[Unit](
        tpe = QueryType,
        fieldMappings =
          List(
            ValueField("collection", _ => collections.head),
            ValueField("collections", _ => collections),
            ValueField("collectionByName", _ => collections)
          )
      ),
      ValueObjectMapping[Collection](
        tpe = CollectionType,
        fieldMappings =
          List(
            ValueField("name", _.name),
            ValueField("itemIds", _.itemIds)
          )
      )
    )

  override val selectElaborator = SelectElaborator {
    case (QueryType, "collectionByName", List(Binding("name", StringValue(name)))) =>
      Elab.transformChild(child => Unique(Filter(Eql(CollectionType / "name", Const(name)), child)))
  }
}

object ItemData {
  case class Item(id: String, name: String)
  val items = List(Item("A", "foo"), Item("B", "bar"), Item("C", "baz"))
}

object ItemMapping extends ValueMapping[IO] {
  import ItemData._

  val schema =
    schema"""
      type Query {
        itemById(id: ID!): Item
      }
      type Item {
        id: String!
        name: String!
      }
    """

  val QueryType = schema.ref("Query")
  val ItemType = schema.ref("Item")

  val typeMappings =
    List(
      ValueObjectMapping[Unit](
        tpe = QueryType,
        fieldMappings =
          List(
            ValueField("itemById", _ => items)
          )
      ),
      ValueObjectMapping[Item](
        tpe = ItemType,
        fieldMappings =
          List(
            ValueField("id", _.id),
            ValueField("name", _.name)
          )
      )
    )

  override val selectElaborator = SelectElaborator {
    case (QueryType, "itemById", List(Binding("id", IDValue(id)))) =>
      Elab.transformChild(child => Unique(Filter(Eql(ItemType / "id", Const(id)), child)))
  }
}

object ComposedListMapping extends ComposedMapping[IO] {
  val schema =
    schema"""
      type Query {
        collection: Collection!
        collections: [Collection!]!
        collectionByName(name: String!): Collection
        itemById(id: ID!): Item
      }
      type Collection {
        name: String!
        itemIds: [String!]!
        items: [Item!]!
      }
      type Item {
        id: String!
        name: String!
      }
    """

  val ItemType = schema.ref("Item")
  val QueryType = schema.ref("Query")
  val CollectionType = schema.ref("Collection")

  val typeMappings =
    List(
      ObjectMapping(
        tpe = QueryType,
        fieldMappings =
          List(
            Delegate("collection", CollectionMapping),
            Delegate("collections", CollectionMapping),
            Delegate("collectionByName", CollectionMapping),
            Delegate("itemById", ItemMapping)
          )
      ),
      ObjectMapping(
        tpe = CollectionType,
        fieldMappings =
          List(
            Delegate("items", ItemMapping, collectionItemJoin)
          )
      )
  )

  override val selectElaborator = SelectElaborator {
    case (QueryType, "itemById", List(Binding("id", IDValue(id)))) =>
      Elab.transformChild(child => Unique(Filter(Eql(ItemType / "id", Const(id)), child)))
    case (QueryType, "collectionByName", List(Binding("name", StringValue(name)))) =>
      Elab.transformChild(child => Unique(Filter(Eql(CollectionType / "name", Const(name)), child)))
  }

  def collectionItemJoin(q: Query, c: Cursor): Result[Query] =
    (c.focus, q) match {
      case (c: CollectionData.Collection, Select("items", _, child)) =>
        Group(c.itemIds.map(id => Select("itemById", Unique(Filter(Eql(ItemType / "id", Const(id)), child))))).success
      case _ =>
        Result.internalError(s"Unexpected cursor focus type in collectionItemJoin")
    }
}

final class ComposedListSuite extends CatsEffectSuite {
  test("simple outer query") {
    val query = """
      query {
        collection {
          itemIds
        }
      }
    """

    val expected = json"""
      {
        "data" : {
          "collection" : {
            "itemIds" : [
              "A",
              "B"
            ]
          }
        }
      }
    """

    val res = CollectionMapping.compileAndRun(query)

    assertIO(res, expected)
  }

  test("simple inner query") {
    val query = """
      query {
        itemById(id: "A") {
          id
          name
        }
      }
    """

    val expected = json"""
      {
        "data" : {
          "itemById" : {
            "id" : "A",
            "name" : "foo"
          }
        }
      }
    """

    val res = ItemMapping.compileAndRun(query)

    assertIO(res, expected)
  }

  test("composed query (1)") {
    val query = """
      query {
        collection {
          itemIds
          items {
            id
            name
          }
        }
      }
    """

    val expected = json"""
      {
        "data" : {
          "collection" : {
            "itemIds" : [
              "A",
              "B"
            ],
            "items" : [
              {
                "id" : "A",
                "name" : "foo"
              },
              {
                "id" : "B",
                "name" : "bar"
              }
            ]
          }
        }
      }
    """

    val res = ComposedListMapping.compileAndRun(query)

    assertIO(res, expected)
  }

  test("composed query (2)") {
    val query = """
      query {
        collectionByName(name: "BC") {
          items {
            id
            name
          }
        }
      }
    """

    val expected = json"""
      {
        "data" : {
          "collectionByName" : {
            "items" : [
              {
                "id" : "B",
                "name" : "bar"
              },
              {
                "id" : "C",
                "name" : "baz"
              }
            ]
          }
        }
      }
    """

    val res = ComposedListMapping.compileAndRun(query)

    assertIO(res, expected)
  }

  test("composed query (3)") {
    val query = """
      query {
        collections {
          name
          items {
            id
          }
        }
      }
    """

    val expected = json"""
      {
        "data" : {
          "collections" : [
            {
              "name" : "AB",
              "items" : [
                {
                  "id" : "A"
                },
                {
                  "id" : "B"
                }
              ]
            },
            {
              "name" : "BC",
              "items" : [
                {
                  "id" : "B"
                },
                {
                  "id" : "C"
                }
              ]
            }
          ]
        }
      }
    """

    val res = ComposedListMapping.compileAndRun(query)

    assertIO(res, expected)
  }

  test("composed query (4)") {
    val query = """
      query {
        collections {
          items {
            id
          }
        }
      }
    """

    val expected = json"""
      {
        "data" : {
          "collections" : [
            {
              "items" : [
                {
                  "id" : "A"
                },
                {
                  "id" : "B"
                }
              ]
            },
            {
              "items" : [
                {
                  "id" : "B"
                },
                {
                  "id" : "C"
                }
              ]
            }
          ]
        }
      }
    """

    val res = ComposedListMapping.compileAndRun(query)

    assertIO(res, expected)
  }
}
