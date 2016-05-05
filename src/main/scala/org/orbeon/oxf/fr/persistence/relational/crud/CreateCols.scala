/**
 * Copyright (C) 2016 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.fr.persistence.relational.crud

import java.sql.{PreparedStatement, Timestamp}
import org.orbeon.oxf.fr.persistence.relational.{Oracle, PostgreSQL}

trait CreateCols extends RequestResponse with Common {

  type ParamSetterFunc = (PreparedStatement, Int) ⇒ Unit
  def param[T](setter: (PreparedStatement) ⇒ ((Int, T) ⇒ Unit), value: ⇒ T): ParamSetterFunc = {
    (ps: PreparedStatement, i: Int) ⇒ setter(ps)(i, value)
  }

  case class Row(
    created      : Timestamp,
    username     : Option[String],
    group        : Option[String],
    formVersion  : Option[Int]
  )

  case class Col(
    included     : Boolean,
    name         : String,
    placeholder  : String,
    paramSetter  : ParamSetterFunc
  )

  def insertCols(
    req          : Request,
    existingRow  : Option[Row],
    delete       : Boolean,
    versionToSet : Int)
    : List[Col]  = {

    val xmlCol           = if (req.provider == Oracle) "xml_clob" else "xml"
    val xmlVal           = if (req.provider == PostgreSQL) "XMLPARSE( DOCUMENT ? )" else "?"
    val isFormDefinition = req.forForm && ! req.forAttachment
    val now              = new Timestamp(System.currentTimeMillis())

    val (xmlOpt, metadataOpt) =
      if (! delete && ! req.forAttachment) {
        val (xml, metadataOpt) = RequestReader.dataAndMetadataAsString(metadata = !req.forData)
        (Some(xml), metadataOpt)
      } else {
        (None, None)
      }

    List(
      Col(
        included    = req.forData && req.provider == Oracle,
        name        = "id",
        placeholder = "?",
        paramSetter = param(_.setTimestamp, existingRow.map(_.created).getOrElse(now))),
      Col(
        included    = true,
        name        = "created",
        placeholder = "?",
        paramSetter = param(_.setTimestamp, existingRow.map(_.created).getOrElse(now))),
      Col(
        included    = true,
        name        = "last_modified_time",
        placeholder = "?",
        paramSetter = param(_.setTimestamp, now)),
      Col(
        included    = true,
        name        = "last_modified_by",
        placeholder = "?",
        paramSetter = param(_.setString, requestUsername.orNull)),
      Col(
        included    = true,
        name        = "app",
        placeholder = "?",
        paramSetter = param(_.setString, req.app)),
      Col(
        included    = true,
        name        = "form",
        placeholder = "?",
        paramSetter = param(_.setString, req.form)),
      Col(
        included    = true,
        name        = "form_version",
        placeholder = "?",
        paramSetter = param(_.setInt, versionToSet)),
      Col(
        included    = req.forData,
        name        = "document_id",
        placeholder = "?",
        paramSetter = param(_.setString, req.dataPart.get.documentId)),
      Col(
        included    = true,
        name        = "deleted",
        placeholder = "?",
        paramSetter = param(_.setString, if (delete) "Y" else "N")),
      Col(
        included    = req.forData,
        name        = "draft",
        placeholder = "?",
        paramSetter = param(_.setString, if (req.dataPart.get.isDraft) "Y" else "N")),
      Col(
        included    = req.forAttachment,
        name        = "file_name",
        placeholder = "?",
        paramSetter = param(_.setString, req.filename.get)),
      Col(
        included    = req.forAttachment,
        name        = "file_content",
        placeholder = "?",
        paramSetter = param(_.setBytes, RequestReader.bytes())),
      Col(
        included    = isFormDefinition,
        name        = "form_metadata",
        placeholder = "?",
        paramSetter = param(_.setString, metadataOpt.orNull)),
      Col(
        included    = req.forData,
        name        = "username",
        placeholder = "?" ,
        paramSetter = param(_.setString, existingRow.flatMap(_.username).getOrElse(requestUsername.orNull))),
      Col(
        included    = req.forData,
        name        = "groupname",
        placeholder = "?",
        paramSetter = param(_.setString, existingRow.flatMap(_.group).getOrElse(requestGroup.orNull))),
      Col(
        included    = ! req.forAttachment ,
        name        = xmlCol,
        placeholder = xmlVal,
        paramSetter = param(_.setString, xmlOpt.orNull))
    )
  }

}
