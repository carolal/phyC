#' Non-small cell lung cancer (NSCLC) dataset
#' 
#' Dataset of non-small cell lung cancer (NSCLC) (Zhang et al., 2014) from the multiregional sequencing variant allele frequency. The dataset contain 10 patients.
#' 
#'
#' @name NSCLC
#' @docType data
#' @format A list of 10 VAF matrices
#' @section Variables:
#' Variables:
#' \itemize{
#' \item chrom The number of chromosome
#' \item pos Genomic position
#' \item DESC Description of the position such as the gene symbol.
#' \item normal The VAF of the normal cells.
#' \item Other columns Each column indicates the VAF of each region
#' }
#' @author Yusuke Matsui & Teppei Shimamura
#' @references Zhang,J. et al. (2014) Intra-tumor heterogeneity in localized lung adenocarcinomas delineated by multi-region sequencing. Science, 346, 256�?259.
NULL
