package com.netflix.spinnaker.clouddriver.azure.resources.vmimage.ops.converters

import com.netflix.spinnaker.clouddriver.azure.AzureOperation
import com.netflix.spinnaker.clouddriver.azure.common.AzureAtomicOperationConverterHelper
import com.netflix.spinnaker.clouddriver.azure.resources.vmimage.ops.UpsertAzureImageTagsAtomicOperation
import com.netflix.spinnaker.clouddriver.azure.resources.vmimage.ops.description.UpsertAzureImageTagsDescription
import com.netflix.spinnaker.clouddriver.orchestration.AtomicOperation
import com.netflix.spinnaker.clouddriver.orchestration.AtomicOperations
import com.netflix.spinnaker.clouddriver.security.AbstractAtomicOperationsCredentialsSupport
import org.springframework.stereotype.Component

@AzureOperation(AtomicOperations.UPSERT_IMAGE_TAGS)
@Component("upsertAzureImageTagsDescription")
class UpsertAzureImageTagsAtomicOperationConverter extends AbstractAtomicOperationsCredentialsSupport {

  @Override
  AtomicOperation convertOperation(Map input) {
    new UpsertAzureImageTagsAtomicOperation(convertDescription(input))
  }

  @Override
  UpsertAzureImageTagsDescription convertDescription(Map input) {
    AzureAtomicOperationConverterHelper.
      convertDescription(input, this, UpsertAzureImageTagsDescription) as UpsertAzureImageTagsDescription
  }
}
