// SPDX-License-Identifier: AGPL-3.0-or-later

package org.pgledger.admin.console;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.pgledger.admin.web.AdminHttpExceptionAdvice;
import org.pgledger.admin.web.FormErrors;
import org.pgledger.application.admin.CommonCodeService;
import org.pgledger.application.admin.CommonCodeService.CodeCommand;
import org.pgledger.domain.error.AdminCode;
import org.pgledger.domain.error.AdminException;
import org.pgledger.domain.rule.CodeDirection;
import org.pgledger.domain.rule.CodeGroup;
import org.pgledger.domain.rule.CodeState;
import org.pgledger.domain.value.OwnerIdentity;
import org.pgledger.domain.value.Token;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The common codes: the list (retired ones included), the form that adds or changes one, and the refusal to delete
 * one. A retired code is made active again only with the explicit confirmation of the form. The form is bound to the
 * closed sets ({@link CodeGroup}, {@link CodeState}, {@link CodeDirection}): a value that is none of them fails the binding, which
 * {@link FormErrors} reports as the failure of invalid input with the allowed values.
 */
@Controller
public class CodesController {

    /**
     * The form; {@code confirmReactivation} is the checkbox that confirms making a retired code active again. A state that
     * is left empty is ACTIVE. The direction is for a code of the group RELATION_KIND; left empty, a new code is CANONICAL and
     * an existing code keeps the direction it has.
     */
    public record CodeForm(CodeGroup group, String code, String nameKo, String nameEn, String descriptionKo,
            String descriptionEn, Integer sortOrder, CodeState state, CodeDirection direction,
            String confirmReactivation) {
    }

    private final Choices choices;
    private final CommonCodeService codes;
    private final FormErrors forms;

    public CodesController(Choices choices, CommonCodeService codes, FormErrors forms) {
        this.choices = choices;
        this.codes = codes;
        this.forms = forms;
    }

    @GetMapping("/codes")
    String page(Model model) {
        model.addAttribute("codes", choices.allCodes());
        model.addAttribute("groups", CodeGroup.values());
        model.addAttribute("states", CodeState.values());
        model.addAttribute("directions", CodeDirection.values());
        return "codes";
    }

    @PostMapping("/codes")
    String set(@Valid @ModelAttribute CodeForm form, BindingResult errors, OwnerIdentity actor,
            HttpServletRequest request, RedirectAttributes flash) {
        AdminHttpExceptionAdvice.recoverHtml(request, "codes", () -> {
            Map<String, Object> model = new LinkedHashMap<>();
            model.put("codes", choices.allCodes());
            model.put("groups", CodeGroup.values());
            model.put("states", CodeState.values());
            model.put("directions", CodeDirection.values());
            model.put("codeForm", form);
            return model;
        });
        forms.raiseIfAny(errors);
        codes.set(new CodeCommand(token(form.group()), form.code(), form.nameKo(), form.nameEn(),
                form.descriptionKo(), form.descriptionEn(), form.sortOrder(), token(form.state()),
                token(form.direction())), form.confirmReactivation() != null, actor);
        flash.addFlashAttribute("flashKey", "flash.codeSaved");
        return "redirect:/codes";
    }

    /** Common codes are never deleted: a retired code stays and is set to INACTIVE instead. */
    @DeleteMapping("/codes")
    String deleteByMethod() {
        throw new AdminException(AdminCode.COMMON_CODE_DELETE_FORBIDDEN, List.of());
    }

    @PostMapping("/codes/delete")
    String deleteByForm() {
        throw new AdminException(AdminCode.COMMON_CODE_DELETE_FORBIDDEN, List.of());
    }

    /** The service takes the texts of the wire; a value that was left empty stays empty (the service defaults it). */
    private static String token(Token value) {
        return value == null ? null : value.token();
    }
}
