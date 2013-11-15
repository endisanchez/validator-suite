package controllers

import org.w3.vs.controllers._
import org.w3.vs.{Metrics, model}
import play.api.i18n.Messages
import org.w3.vs.view.Forms._

object User extends VSController {

  val logger = play.Logger.of("controllers.User")

  import scala.concurrent.ExecutionContext.Implicits.global

  def profile = AuthenticatedAction("back.account") { implicit req => user =>
    Ok(views.html.profile(
      userForm = AccountForm(user),
      passwordForm = PasswordForm(user),
      user = user))
  }

  def editAction: ActionA = AuthenticatedAction("form.editAccount") { implicit req => user =>
    AccountForm.bindFromRequest().fold(
      form => {
        Metrics.form.editAccountFailure()
        render {
          case Accepts.Html() => BadRequest(views.html.profile(form, PasswordForm(user), user))
          case Accepts.Json() => BadRequest
        }
      },
      account => {
        for {
          saved <- model.User.update(account.update(user))
        } yield {
          logger.info(s"""id=${user.id} action=editprofile message="profile updated" """)
          render {
            case Accepts.Html() => SeeOther(routes.User.profile().url).withSession(("email" -> saved.email)).flashing(("success" -> Messages("user.profile.updated")))
            case Accepts.Json() => Ok
          }
        }
      }
    )


  }

  def changePasswordAction = AuthenticatedAction("form.editPassword") { implicit req => user =>
    PasswordForm(user).bindFromRequest().fold (
      formWithErrors => {
        Metrics.form.editPasswordFailure()
        BadRequest(views.html.profile(AccountForm(user), formWithErrors, user))
      },
      password => {
        for {
          saved <- model.User.update(user.withPassword(password.newPassword))
        } yield {
          logger.info(s"""id=${user.id} action=editpassword message="password updated" """)
          SeeOther(routes.User.profile().url).flashing(("success" -> Messages("user.password.updated")))
        }
      }
    )
  }

}
